package mdt;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Service.State;

import utils.Preconditions;

import mdt.client.support.MqttBrokerConfig;
import mdt.client.support.MqttService;


/**
 * MQTT broker 연결({@link MqttService})을 broker 설정별로 공유하는 pool.
 * <p>
 * 같은 broker 설정(URL, 연결 timeout, keep-alive 주기)으로 {@link #acquire(MqttBrokerConfig)}를 호출하면
 * 하나의 {@link MqttService}를 함께 사용한다. 처음 획득할 때 서비스를 생성하여 시작하고, 획득한
 * {@link Lease}가 모두 반납되면 서비스를 중지하여 broker와의 연결을 끊는다.
 * <p>
 * 공유되는 서비스이므로 사용자는 획득한 서비스를 직접 시작하거나 중지해서는 안 되며,
 * 구독한 topic은 {@link Lease}를 반납하기 전에 직접 구독 해제해야 한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class MqttServicePool {
	private static final Logger s_logger = LoggerFactory.getLogger(MqttServicePool.class);
	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

	// 같은 연결을 공유할 수 있는지 판단하는 키
	private record BrokerKey(String brokerUrl, String connectionTimeout, String keepAliveInterval) {
		static BrokerKey of(MqttBrokerConfig config) {
			return new BrokerKey(config.getBrokerUrl(), config.getConnectionTimeout(),
									config.getKeepAliveInterval());
		}
	}

	private static final class Entry {
		private final BrokerKey m_key;
		private final MqttService m_service;
		private int m_refCount = 0;		// guarded by MqttServicePool.class

		Entry(BrokerKey key, MqttService service) {
			m_key = key;
			m_service = service;
		}
	}

	private static final Map<BrokerKey,Entry> s_entries = Maps.newHashMap();	// guarded by MqttServicePool.class

	private MqttServicePool() {
		throw new AssertionError("Should not be called: class=" + MqttServicePool.class);
	}

	/**
	 * 주어진 broker 설정에 해당하는 {@link MqttService}의 사용권을 획득한다.
	 * <p>
	 * 해당 broker에 대한 서비스가 없거나 이미 종료된 경우에는 새로 생성하여 시작한다.
	 * 서비스는 broker 연결을 비동기로 시도하므로, 반환 시점에 연결이 완료되었다는 보장은 없다.
	 *
	 * @param config	MQTT broker 설정.
	 * @return	서비스 사용권. 사용이 끝나면 반드시 {@link Lease#close()}로 반납해야 한다.
	 */
	public static Lease acquire(MqttBrokerConfig config) {
		Preconditions.checkNotNullArgument(config, "MqttBrokerConfig is null");

		BrokerKey key = BrokerKey.of(config);
		Entry entry;
		boolean created = false;
		synchronized ( MqttServicePool.class ) {
			entry = s_entries.get(key);
			if ( entry == null || !isUsable(entry.m_service) ) {
				entry = new Entry(key, new MqttService(config));
				s_entries.put(key, entry);
				created = true;
			}
			++entry.m_refCount;
		}

		if ( created ) {
			s_logger.info("created a shared MQTT connection: broker={}", key.brokerUrl());
			entry.m_service.startAsync();
		}
		return new Lease(entry);
	}

	/**
	 * 현재 공유 중인 broker 연결의 수를 반환한다.
	 *
	 * @return	broker 연결 수.
	 */
	public static int size() {
		synchronized ( MqttServicePool.class ) {
			return s_entries.size();
		}
	}

	private static boolean isUsable(MqttService service) {
		State state = service.state();
		return state == State.NEW || state == State.STARTING || state == State.RUNNING;
	}

	private static void release(Entry entry) {
		synchronized ( MqttServicePool.class ) {
			if ( --entry.m_refCount > 0 ) {
				return;
			}
			// 이미 종료되어 다른 entry로 교체된 경우에는 map에서 제거하지 않는다.
			if ( s_entries.get(entry.m_key) == entry ) {
				s_entries.remove(entry.m_key);
			}
		}

		// 중지 대기 동안 다른 broker의 획득·반납을 막지 않도록 lock 밖에서 중지시킨다.
		s_logger.info("closing the shared MQTT connection: broker={}", entry.m_key.brokerUrl());
		try {
			entry.m_service.stopAsync().awaitTerminated(STOP_TIMEOUT);
		}
		catch ( TimeoutException e ) {
			s_logger.warn("timeout while stopping {}: timeout={}", entry.m_service, STOP_TIMEOUT);
		}
		catch ( IllegalStateException e ) {
			// 서비스가 FAILED 상태로 종료된 경우.
			s_logger.warn("{} has failed: cause={}", entry.m_service, "" + entry.m_service.failureCause());
		}
	}

	/**
	 * {@link MqttServicePool}에서 획득한 {@link MqttService}의 사용권.
	 * <p>
	 * {@link #close()}는 여러 번 호출해도 한 번만 반납된다.
	 */
	public static final class Lease implements AutoCloseable {
		private final Entry m_entry;
		private boolean m_closed = false;	// guarded by this

		private Lease(Entry entry) {
			m_entry = entry;
		}

		/**
		 * 공유된 {@link MqttService}를 반환한다.
		 * <p>
		 * 반환된 서비스를 직접 시작하거나 중지해서는 안 된다.
		 *
		 * @return	MQTT 서비스.
		 */
		public MqttService getService() {
			return m_entry.m_service;
		}

		/**
		 * 사용권을 반납한다. 마지막 사용권이 반납되면 서비스를 중지하고 종료될 때까지(최대 5초) 기다린다.
		 */
		@Override
		public void close() {
			synchronized ( this ) {
				if ( m_closed ) {
					return;
				}
				m_closed = true;
			}
			release(m_entry);
		}

		@Override
		public String toString() {
			return String.format("MqttServicePool.Lease[broker=%s]", m_entry.m_key.brokerUrl());
		}
	}
}
