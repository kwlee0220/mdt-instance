package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import de.fraunhofer.iosb.ilt.faaast.service.model.value.PropertyValue;

import mdt.ElementLocation;
import mdt.ElementValueUpdater;


/**
 * {@link MqttSubscriber}가 반영 작업을 작업 쓰레드로 넘기고, {@link DeliveryMode}에 따라
 * 모든 메시지 또는 최신 값만 반영하는지 검증한다.
 * <p>
 * 첫 반영을 latch로 붙잡아 반영이 밀린 상황을 만든다. 콜백 쓰레드(테스트 쓰레드)가 붙잡히면 테스트는 시간 초과로 실패한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttSubscriberDeliveryTest {
	private static final String TOPIC = "factory/robot01/temperature";

	private ExecutorService m_workers;
	private Logger m_logger;
	private ListAppender<ILoggingEvent> m_appender;

	private final List<String> m_applied = new CopyOnWriteArrayList<>();
	private final CountDownLatch m_firstStarted = new CountDownLatch(1);
	private final CountDownLatch m_release = new CountDownLatch(1);

	@Before
	public void setUp() {
		m_workers = Executors.newCachedThreadPool();
		m_logger = (Logger)LoggerFactory.getLogger(MqttEndpoint.class);
		m_appender = new ListAppender<>();
		m_appender.start();
		m_logger.addAppender(m_appender);
	}

	@After
	public void tearDown() throws InterruptedException {
		m_release.countDown();
		m_workers.shutdownNow();
		m_workers.awaitTermination(5, TimeUnit.SECONDS);
		m_logger.detachAppender(m_appender);
	}

	@Test(timeout = 10_000)
	public void testLatestKeepsOnlyNewestPendingValue() throws Exception {
		MqttSubscriber subscriber = subscriber(DeliveryMode.LATEST, MqttSubscriber.DEFAULT_CAPACITY);

		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));

		// 첫 반영이 붙잡혀 있어도 콜백은 바로 반환한다. 2, 3은 4로 덮여 버려진다.
		subscriber.onMessage(TOPIC, message("2"));
		subscriber.onMessage(TOPIC, message("3"));
		subscriber.onMessage(TOPIC, message("4"));

		m_release.countDown();
		awaitApplied(2);
		Thread.sleep(100);		// 더 반영되는 값이 없는지 확인한다.
		assertEquals(List.of("1", "4"), m_applied);
		assertEquals(2, subscriber.getDroppedCount());
	}

	@Test(timeout = 10_000)
	public void testAllAppliesEveryMessageInOrder() throws Exception {
		MqttSubscriber subscriber = subscriber(DeliveryMode.ALL, MqttSubscriber.DEFAULT_CAPACITY);

		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));
		subscriber.onMessage(TOPIC, message("2"));
		subscriber.onMessage(TOPIC, message("3"));
		subscriber.onMessage(TOPIC, message("4"));

		m_release.countDown();
		awaitApplied(4);
		assertEquals(List.of("1", "2", "3", "4"), m_applied);
		assertEquals(0, subscriber.getDroppedCount());
	}

	@Test(timeout = 10_000)
	public void testAllWaitsWhenBacklogIsFull() throws Exception {
		// 대기열 크기 1: 첫 메시지가 반영 중인 동안 둘째 메시지는 자리가 날 때까지 기다려야 한다(버리지 않음).
		MqttSubscriber subscriber = subscriber(DeliveryMode.ALL, 1);

		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));

		Thread callback = new Thread(() -> subscriber.onMessage(TOPIC, message("2")));
		callback.start();
		callback.join(300);
		assertTrue("the callback must wait for a free slot", callback.isAlive());

		m_release.countDown();
		callback.join(5_000);
		assertFalse(callback.isAlive());
		awaitApplied(2);
		assertEquals(List.of("1", "2"), m_applied);
		assertEquals(1, warnings().stream().filter(msg -> msg.contains("in-flight limit (1) reached, waiting")).count());
	}

	@Test(timeout = 10_000)
	public void testCloseDiscardsPendingMessages() throws Exception {
		MqttSubscriber subscriber = subscriber(DeliveryMode.LATEST, MqttSubscriber.DEFAULT_CAPACITY);

		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));
		subscriber.onMessage(TOPIC, message("2"));		// 반영 대기

		subscriber.close();
		subscriber.onMessage(TOPIC, message("3"));		// close 이후에는 받지 않는다.
		m_release.countDown();

		m_workers.shutdown();
		assertTrue(m_workers.awaitTermination(5, TimeUnit.SECONDS));
		assertEquals("only the update in progress completes", List.of("1"), m_applied);
	}

	@Test(timeout = 10_000)
	public void testCloseWakesWaitingCallback() throws Exception {
		// MQTT-01: 상한 때문에 기다리던 콜백 쓰레드는 close()로 깨어나 메시지를 버려야 한다.
		MqttSubscriber subscriber = subscriber(DeliveryMode.ALL, 1);
		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));
		
		Thread callback = new Thread(() -> subscriber.onMessage(TOPIC, message("2")));
		callback.start();
		callback.join(300);
		assertTrue("the callback must be waiting for a slot", callback.isAlive());
		
		subscriber.close();
		callback.join(2_000);
		assertFalse("close() must wake the waiting callback", callback.isAlive());
		
		m_release.countDown();
		m_workers.shutdown();
		assertTrue(m_workers.awaitTermination(5, TimeUnit.SECONDS));
		assertEquals("the message woken by close() must not be applied", List.of("1"), m_applied);
	}

	@Test(timeout = 10_000)
	public void testDropPolicyDropsWithoutWaiting() throws Exception {
		MqttSubscriber subscriber = subscriber(new MqttElementSubscriber(TOPIC, "State:Temperature", DeliveryMode.ALL,
																		null, null, 1, OverflowPolicy.DROP), 1);
		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));
		
		// 자리가 없으면 기다리지 않고 버린다.
		subscriber.onMessage(TOPIC, message("2"));
		subscriber.onMessage(TOPIC, message("3"));
		assertEquals(2, subscriber.getDroppedCount());
		assertEquals(1, warnings().stream().filter(msg -> msg.contains("in-flight limit (1) reached, dropped")).count());
		
		m_release.countDown();
		awaitApplied(1);
		subscriber.onMessage(TOPIC, message("4"));		// 자리가 나면 다시 받는다.
		awaitApplied(2);
		assertEquals(List.of("1", "4"), m_applied);
	}

	@Test(timeout = 10_000)
	public void testRejectedExecutionReleasesSlot() {
		// 작업 쓰레드가 이미 정리되어 제출이 거절되어도 자리와 대기 값이 남지 않아야 한다.
		java.util.concurrent.Executor rejecting = task -> {
			throw new java.util.concurrent.RejectedExecutionException("shut down");
		};
		MqttElementSubscriber allMapping = new MqttElementSubscriber(TOPIC, "State:Temperature", DeliveryMode.ALL);
		MqttSubscriber all = new MqttSubscriber(allMapping, recordingUpdater(allMapping), rejecting, 1);
		all.onMessage(TOPIC, message("1"));
		all.onMessage(TOPIC, message("2"));		// 자리가 반환되지 않았다면 여기서 영원히 기다린다.
		
		MqttElementSubscriber latestMapping = new MqttElementSubscriber(TOPIC, "State:Temperature", DeliveryMode.LATEST);
		MqttSubscriber latest = new MqttSubscriber(latestMapping, recordingUpdater(latestMapping), rejecting, 1);
		latest.onMessage(TOPIC, message("1"));
		latest.onMessage(TOPIC, message("2"));
		assertEquals("a rejected value must not stay pending and be counted as replaced", 0, latest.getDroppedCount());
		assertTrue(m_applied.isEmpty());
	}
	
	private ElementValueUpdater recordingUpdater(MqttElementSubscriber mapping) {
		ElementLocation loc = mapping.getElementLocation();
		return new ElementValueUpdater() {
			@Override public ElementLocation getLocation() { return loc; }
			@Override public Class<?> getValueType() { return PropertyValue.class; }
			@Override public void update(String valueJson) { m_applied.add(valueJson); }
		};
	}
	
	private MqttSubscriber subscriber(DeliveryMode delivery, int capacity) {
		return subscriber(new MqttElementSubscriber(TOPIC, "State:Temperature", delivery), capacity);
	}
	
	private MqttSubscriber subscriber(MqttElementSubscriber mapping, int capacity) {
		ElementLocation loc = mapping.getElementLocation();
		ElementValueUpdater updater = new ElementValueUpdater() {
			@Override public ElementLocation getLocation() { return loc; }
			@Override public Class<?> getValueType() { return PropertyValue.class; }
			@Override public void update(String valueJson) {
				if ( m_firstStarted.getCount() > 0 ) {
					m_firstStarted.countDown();
					try {
						m_release.await();
					}
					catch ( InterruptedException e ) {
						Thread.currentThread().interrupt();
					}
				}
				m_applied.add(valueJson);
			}
		};
		return new MqttSubscriber(mapping, updater, m_workers, capacity);
	}

	private void awaitApplied(int count) throws InterruptedException {
		while ( m_applied.size() < count ) {
			Thread.sleep(10);
		}
	}

	private List<String> warnings() {
		synchronized ( m_appender ) {
			return m_appender.list.stream()
								.filter(ev -> ev.getLevel() == Level.WARN)
								.map(ILoggingEvent::getFormattedMessage)
								.toList();
		}
	}

	private static MqttMessage message(String payload) {
		return new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
	}
}
