package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;

import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.PropertyValue;

import mdt.ElementLocation;
import mdt.ElementValueUpdater;
import mdt.MqttServicePool;
import mdt.config.ServiceEndpointConfigs;
import mdt.model.MDTModelSerDe;


/**
 * {@link MqttEndpoint}의 시작·종료 자원 관리(MQTT-03)를 broker 없이 검증한다.
 * 연결할 수 없는 주소의 인라인 broker 설정을 쓰므로 broker 구독은 연결 뒤로 미뤄지고, 로컬 등록과 자원 정리만 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttEndpointLifecycleTest {
	private static final String UNREACHABLE = "tcp://127.0.0.1:1";

	@Test
	public void testDuplicateStartIsRejected() throws Exception {
		MqttEndpoint endpoint = endpoint(UNREACHABLE);
		int poolSize = MqttServicePool.size();
		ExecutorService firstWorkers = Executors.newCachedThreadPool();
		endpoint.startSession(List.of(subscriber("a", firstWorkers)), firstWorkers);
		assertTrue(endpoint.isStarted());
		assertEquals(poolSize + 1, MqttServicePool.size());
		
		ExecutorService secondWorkers = Executors.newCachedThreadPool();
		assertThrows(IllegalStateException.class,
					() -> endpoint.startSession(List.of(subscriber("b", secondWorkers)), secondWorkers));
		assertTrue("the rejected start must not leak its workers", secondWorkers.isShutdown());
		assertEquals("the rejected start must not acquire another connection", poolSize + 1, MqttServicePool.size());
		
		MqttEndpoint.StopResult result = endpoint.stop(Duration.ofSeconds(1));
		assertNotNull(result);
		assertTrue(result.isClean());
		assertFalse(endpoint.isStarted());
		assertTrue(firstWorkers.isTerminated());
		assertEquals(poolSize, MqttServicePool.size());
	}

	@Test
	public void testStopIsIdempotentAndRestartWorks() throws Exception {
		MqttEndpoint endpoint = endpoint(UNREACHABLE);
		int poolSize = MqttServicePool.size();
		assertNull("stop before start does nothing", endpoint.stop(Duration.ofSeconds(1)));
		
		ExecutorService workers = Executors.newCachedThreadPool();
		MqttSubscriber subscriber = subscriber("a", workers);
		endpoint.startSession(List.of(subscriber), workers);
		endpoint.stop();
		assertTrue("stop() must close subscribers", subscriber.isClosed());
		assertNull("the second stop does nothing", endpoint.stop(Duration.ofSeconds(1)));
		
		ExecutorService workers2 = Executors.newCachedThreadPool();
		endpoint.startSession(List.of(subscriber("a", workers2)), workers2);
		assertTrue(endpoint.isStarted());
		endpoint.stop();
		assertEquals(poolSize, MqttServicePool.size());
	}

	@Test
	public void testEmptySubscriptionsDoNotConnect() throws Exception {
		MqttEndpoint endpoint = endpoint(UNREACHABLE);
		int poolSize = MqttServicePool.size();
		ExecutorService workers = Executors.newCachedThreadPool();
		endpoint.startSession(List.of(), workers);
		assertTrue(endpoint.isStarted());
		assertTrue(workers.isShutdown());
		assertEquals(poolSize, MqttServicePool.size());
		endpoint.stop();
	}

	@Test
	public void testFailedStartReleasesResources() throws Exception {
		// 연결 사용권 획득이 실패(잘못된 broker URL)하면 이미 만든 작업 쓰레드와 구독자를 정리하고 원인을 남긴다.
		MqttEndpoint endpoint = endpoint("invalid://no-such-scheme");
		int poolSize = MqttServicePool.size();
		ExecutorService workers = Executors.newCachedThreadPool();
		MqttSubscriber subscriber = subscriber("a", workers);
		
		EndpointException e = assertThrows(EndpointException.class,
											() -> endpoint.startSession(List.of(subscriber), workers));
		assertNotNull(e.getCause());
		assertFalse(endpoint.isStarted());
		assertTrue(workers.isShutdown());
		assertTrue(subscriber.isClosed());
		assertEquals(poolSize, MqttServicePool.size());
	}
	
	private static MqttEndpoint endpoint(String brokerUrl) throws Exception {
		ServiceEndpointConfigs configs = MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class)
				.readValue("{ \"mqtt\": { \"mqttBrokerConfig\": { \"brokerUrl\": \"" + brokerUrl + "\" } } }");
		MqttEndpoint endpoint = new MqttEndpoint();
		endpoint.init(null, new MqttEndpointConfig(configs.getMqtt()), null);
		return endpoint;
	}
	
	private static MqttSubscriber subscriber(String topic, ExecutorService workers) {
		MqttElementSubscriber mapping = new MqttElementSubscriber(topic, "State:" + topic);
		ElementLocation loc = mapping.getElementLocation();
		ElementValueUpdater updater = new ElementValueUpdater() {
			@Override public ElementLocation getLocation() { return loc; }
			@Override public Class<?> getValueType() { return PropertyValue.class; }
			@Override public void update(String valueJson) { }
		};
		return new MqttSubscriber(mapping, updater, workers);
	}
}
