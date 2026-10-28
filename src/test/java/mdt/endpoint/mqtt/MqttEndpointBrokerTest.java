package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import de.fraunhofer.iosb.ilt.faaast.service.model.value.PropertyValue;

import mdt.ElementLocation;
import mdt.ElementValueUpdater;
import mdt.MqttServicePool;
import mdt.client.support.MqttBrokerConfig;
import mdt.client.support.MqttService;
import mdt.config.ServiceEndpointConfigs;
import mdt.model.MDTModelSerDe;


/**
 * 실제 MQTT broker로 공유 연결의 교착과 잘못된 topic 처리를 검증한다(MQTT-01, MQTT-02).
 * <p>
 * 환경 변수 {@code MDT_TEST_MQTT_BROKER}(예: {@code tcp://127.0.0.1:50125})가 있을 때만 실행하며, 없으면 건너뛴다.
 * 테스트용 broker는 예를 들어 {@code mosquitto -c <conf>}로 별도 포트에 띄운다(익명 접속 허용).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttEndpointBrokerTest {
	private String m_brokerUrl;

	@Before
	public void setUp() {
		m_brokerUrl = System.getenv("MDT_TEST_MQTT_BROKER");
		Assume.assumeTrue("MDT_TEST_MQTT_BROKER is not set", m_brokerUrl != null && !m_brokerUrl.isBlank());
	}

	@Test(timeout = 60_000)
	public void testStopDoesNotDeadlockWhenSaturated() throws Exception {
		MqttBrokerConfig brokerConfig = new MqttBrokerConfig();
		brokerConfig.setBrokerUrl(m_brokerUrl);

		// 같은 broker 연결을 쓰는 다른 사용자.
		MqttServicePool.Lease other = MqttServicePool.acquire(brokerConfig);
		List<String> otherReceived = new CopyOnWriteArrayList<>();
		MqttClient publisher = new MqttClient(m_brokerUrl, MqttClient.generateClientId(), new MemoryPersistence());
		try {
			awaitConnected(other.getService());
			other.getService().subscribe("review/other", 0,
								(t, m) -> otherReceived.add(new String(m.getPayload(), StandardCharsets.UTF_8)));
			publisher.connect();

			// 상한 1, 첫 반영이 멈춘 구독. 이후 메시지는 콜백 쓰레드를 붙잡아 Paho 수신 대기열까지 채운다.
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			MqttEndpoint endpoint = endpoint();
			ExecutorService workers = Executors.newCachedThreadPool();
			MqttElementSubscriber mapping = new MqttElementSubscriber("review/saturated", "State:Value", DeliveryMode.ALL,
																	0, null, 1, null);
			MqttSubscriber subscriber = new MqttSubscriber(mapping, updater(mapping, () -> {
				started.countDown();
				try {
					release.await();
				}
				catch ( InterruptedException e ) {
					Thread.currentThread().interrupt();
				}
			}), workers);
			endpoint.startSession(List.of(subscriber), workers);

			publish(publisher, "review/saturated", "1");
			assertTrue(started.await(10, TimeUnit.SECONDS));
			for ( int i = 0; i < 40; ++i ) {
				publish(publisher, "review/saturated", "2");
			}
			Thread.sleep(500);		// 콜백 쓰레드가 자리를 기다리고 Paho 대기열이 차도록 둔다.

			// 반영이 멈춘 채로 종료한다. 예전에는 구독 해제가 공유 잠금 안에서 응답을 기다려 교착되었다.
			AtomicReference<MqttEndpoint.StopResult> result = new AtomicReference<>();
			Thread stopper = new Thread(() -> result.set(endpoint.stop(Duration.ofSeconds(1))), "review-stop");
			long startNanos = System.nanoTime();
			stopper.start();
			stopper.join(20_000);
			assertFalse("stop() must not deadlock", stopper.isAlive());
			long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
			assertNotNull(result.get());
			assertEquals("the unsubscription must be confirmed", 0, result.get().unsubscribeFailures());
			assertTrue("the blocked update must be reported", result.get().workersTimedOut());
			assertTrue("stop took " + elapsedMillis + "ms", elapsedMillis < 15_000);
			release.countDown();

			// 같은 연결의 다른 사용자는 계속 수신할 수 있어야 한다.
			publish(publisher, "review/other", "still-alive");
			long due = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while ( !otherReceived.contains("still-alive") && System.nanoTime() < due ) {
				Thread.sleep(20);
			}
			assertTrue("the shared connection must keep working: " + otherReceived, otherReceived.contains("still-alive"));
		}
		finally {
			if ( publisher.isConnected() ) {
				publisher.disconnect();
			}
			publisher.close();
			other.close();
		}
	}

	@Test(timeout = 30_000)
	public void testInvalidTopicIsRejectedWhileConnected() throws Exception {
		MqttBrokerConfig brokerConfig = new MqttBrokerConfig();
		brokerConfig.setBrokerUrl(m_brokerUrl);
		MqttServicePool.Lease lease = MqttServicePool.acquire(brokerConfig);
		MqttClient publisher = new MqttClient(m_brokerUrl, MqttClient.generateClientId(), new MemoryPersistence());
		try {
			MqttService service = lease.getService();
			awaitConnected(service);

			// MQTT-02: 연결된 상태에서도 잘못된 topic은 상태를 바꾸지 않고 거부되며, 연결은 계속 쓸 수 있다.
			assertThrows(IllegalArgumentException.class, () -> service.subscribe("a/#/b", 0, (t, m) -> { }));

			List<String> received = new CopyOnWriteArrayList<>();
			MqttService.Subscription sub = service.subscribe("review/valid", 0,
								(t, m) -> received.add(new String(m.getPayload(), StandardCharsets.UTF_8)));
			publisher.connect();
			publish(publisher, "review/valid", "ok");
			long due = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while ( received.isEmpty() && System.nanoTime() < due ) {
				Thread.sleep(20);
			}
			assertEquals(List.of("ok"), received);
			assertTrue(sub.unsubscribe());
		}
		finally {
			if ( publisher.isConnected() ) {
				publisher.disconnect();
			}
			publisher.close();
			lease.close();
		}
	}

	private MqttEndpoint endpoint() throws Exception {
		ServiceEndpointConfigs configs = MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class)
				.readValue("{ \"mqtt\": { \"mqttBrokerConfig\": { \"brokerUrl\": \"" + m_brokerUrl + "\" } } }");
		MqttEndpoint endpoint = new MqttEndpoint();
		endpoint.init(null, new MqttEndpointConfig(configs.getMqtt()), null);
		return endpoint;
	}

	private static ElementValueUpdater updater(MqttElementSubscriber mapping, Runnable action) {
		ElementLocation loc = mapping.getElementLocation();
		return new ElementValueUpdater() {
			@Override public ElementLocation getLocation() { return loc; }
			@Override public Class<?> getValueType() { return PropertyValue.class; }
			@Override public void update(String valueJson) { action.run(); }
		};
	}

	private static void awaitConnected(MqttService service) throws InterruptedException {
		long due = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while ( !service.isConnected() && System.nanoTime() < due ) {
			Thread.sleep(20);
		}
		assertTrue("not connected to the test broker", service.isConnected());
	}

	private static void publish(MqttClient publisher, String topic, String payload) throws Exception {
		MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
		msg.setQos(0);
		publisher.publish(topic, msg);
	}
}
