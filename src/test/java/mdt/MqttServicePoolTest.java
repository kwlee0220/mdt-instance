package mdt;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import com.google.common.util.concurrent.Service.State;

import mdt.client.support.MqttBrokerConfig;
import mdt.client.support.MqttService;


/**
 * 접속할 수 없는 broker 주소를 사용하므로, 서비스는 RUNNING 상태에서 연결 재시도만 반복한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttServicePoolTest {
	private static final String UNREACHABLE_BROKER = "tcp://127.0.0.1:1";

	@After
	public void tearDown() {
		assertEquals("leaked shared MQTT connections", 0, MqttServicePool.size());
	}

	@Test
	public void testShareAndRelease() throws Exception {
		MqttServicePool.Lease lease1 = MqttServicePool.acquire(config(UNREACHABLE_BROKER, null));
		MqttServicePool.Lease lease2 = MqttServicePool.acquire(config(UNREACHABLE_BROKER, null));
		MqttService service = lease1.getService();

		assertSame(service, lease2.getService());
		assertEquals(1, MqttServicePool.size());

		lease1.close();
		assertEquals(State.RUNNING, service.state());
		assertEquals(1, MqttServicePool.size());

		lease2.close();
		assertEquals(State.TERMINATED, service.state());
		assertEquals(0, MqttServicePool.size());
	}

	@Test
	public void testDoubleCloseReleasesOnce() throws Exception {
		MqttServicePool.Lease lease1 = MqttServicePool.acquire(config(UNREACHABLE_BROKER, null));
		MqttServicePool.Lease lease2 = MqttServicePool.acquire(config(UNREACHABLE_BROKER, null));
		MqttService service = lease1.getService();

		lease1.close();
		lease1.close();
		assertEquals(State.RUNNING, service.state());

		lease2.close();
		assertEquals(State.TERMINATED, service.state());
	}

	@Test
	public void testDifferentOptionsUseDifferentConnections() throws Exception {
		try ( MqttServicePool.Lease lease1 = MqttServicePool.acquire(config(UNREACHABLE_BROKER, null));
				MqttServicePool.Lease lease2 = MqttServicePool.acquire(config(UNREACHABLE_BROKER, "30s")) ) {
			assertNotSame(lease1.getService(), lease2.getService());
			assertEquals(2, MqttServicePool.size());
		}
	}

	@Test
	public void testReacquireAfterRelease() throws Exception {
		MqttService first;
		try ( MqttServicePool.Lease lease = MqttServicePool.acquire(config(UNREACHABLE_BROKER, null)) ) {
			first = lease.getService();
		}

		try ( MqttServicePool.Lease lease = MqttServicePool.acquire(config(UNREACHABLE_BROKER, null)) ) {
			assertNotSame(first, lease.getService());
			assertEquals(State.RUNNING, lease.getService().state());
		}
	}

	private static MqttBrokerConfig config(String brokerUrl, String keepAlive) {
		MqttBrokerConfig config = new MqttBrokerConfig();
		config.setBrokerUrl(brokerUrl);
		config.setKeepAliveInterval(keepAlive);
		return config;
	}
}
