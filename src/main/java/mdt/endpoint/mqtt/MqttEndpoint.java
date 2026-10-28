package mdt.endpoint.mqtt;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.model.ServiceSpecificationProfile;
import de.fraunhofer.iosb.ilt.faaast.service.starter.InitializationException;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import utils.KeyValue;

import mdt.ElementLocation;
import mdt.ElementValueUpdater;
import mdt.FaaastRuntime;
import mdt.MqttServicePool;
import mdt.client.support.MqttBrokerConfig;
import mdt.persistence.MDTModelLookup;

/**
 * MQTT 메시지를 받아 AAS 요소 값을 갱신하는 FA³ST {@link Endpoint}.
 * <p>
 * 설정({@link MqttEndpointConfig})의 구독마다 토픽을 구독하고, 받은 payload(AAS value-only JSON)를 대상 요소에 반영한다.
 * <ul>
 *   <li>broker 연결은 {@link MqttServicePool}로 같은 broker를 쓰는 MQTT 자산 변수와 공유한다.</li>
 *   <li>{@link #start()}에서 구독마다 대상 요소를 확인하고, 요소가 없는 구독은 ERROR 로그를 남기고 뺀다.</li>
 *   <li>메시지 반영은 MQTT 콜백 쓰레드가 아닌 이 엔드포인트의 작업 쓰레드에서 구독별로 순서대로 수행하며,
 *       반영 방식은 구독의 {@link DeliveryMode}를 따른다({@link MqttSubscriber}).</li>
 * </ul>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttEndpoint implements Endpoint<MqttEndpointConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(MqttEndpoint.class);
	// stop()에서 반영 중인 메시지가 끝나기를 기다리는 시간
	private static final Duration WORKER_STOP_TIMEOUT = Duration.ofSeconds(5);

	private FaaastRuntime m_faaast;
	private MqttEndpointConfig m_config;
	private MqttBrokerConfig m_brokerConfig;
	private MqttServicePool.Lease m_lease;		// 공유 연결 사용권 (start() 이후에만 존재)
	private final List<KeyValue<String,MqttSubscriber>> m_subscriptions = Lists.newArrayList();
	private ExecutorService m_workers;			// 메시지를 요소에 반영하는 작업 쓰레드 (start() 이후에만 존재)

	@Override
	public void init(CoreConfig coreConfig, MqttEndpointConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		m_config = config;
		
		m_faaast = FaaastRuntime.getOrCreate(serviceContext);
		
		try {
			m_brokerConfig = m_config.getMqttBrokerConfig();
			s_logger.info("Initialized MqttEndpoint: {}", m_brokerConfig);
		}
		catch ( InitializationException e ) {
			throw e;
		}
		catch ( Exception e ) {
			throw new ConfigurationInitializationException("failed to resolve the MQTT broker config: " + m_config
															+ ", cause=" + e, e);
		}
	}

	@Override
	public MqttEndpointConfig asConfig() {
		return m_config;
	}

	@Override
	public List<ServiceSpecificationProfile> getProfiles() {
		return m_config.getProfiles();
	}
	
	/**
	 * 구독을 시작한다.
	 * <p>
	 * 구독마다 대상 요소 위치를 해석하고 값 갱신기를 만들어 요소가 있는지 확인한다. 대상 요소를 찾지 못한 구독은
	 * ERROR 로그를 남기고 제외하며, 나머지 구독은 계속 진행한다. 남은 구독이 없으면 broker에 연결하지 않는다.
	 * <p>
	 * 수신한 메시지는 MQTT 콜백 쓰레드가 아니라 이 엔드포인트의 작업 쓰레드에서 요소에 반영한다
	 * ({@link MqttSubscriber}, {@link DeliveryMode}).
	 */
    @Override
    public void start() throws EndpointException {
		MDTModelLookup lookup = MDTModelLookup.getInstance();
		ExecutorService workers = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
																	.setNameFormat("mqtt-endpoint-%d")
																	.setDaemon(true)
																	.build());
		List<MqttSubscriber> subscribers = createSubscribers(m_config.getSubscribers(), loc -> {
			loc.activate(lookup);
			return m_faaast.newValueUpdater(loc);
		}, workers);
		s_logger.info("Starting service: {}, subscriptions={}/{}", this, subscribers.size(),
						m_config.getSubscribers().size());
		if ( subscribers.isEmpty() ) {
			workers.shutdown();
			return;
		}
		m_workers = workers;

		// 같은 broker를 사용하는 MQTT AssetVariable들과 연결을 공유한다.
		// 공유 연결이 이미 맺어져 있을 수 있으므로, 갱신기를 모두 만든 뒤에 구독한다.
		m_lease = MqttServicePool.acquire(m_brokerConfig);
		for ( MqttSubscriber subscriber: subscribers ) {
			m_lease.getService().subscribe(subscriber.getTopic(), 1, subscriber);
			m_subscriptions.add(KeyValue.of(subscriber.getTopic(), subscriber));
		}
    }
	
	/**
	 * 구독 설정마다 값 갱신기를 만들어 구독자를 생성한다.
	 * <p>
	 * 대상 요소 위치를 해석하지 못하거나 요소가 없어 갱신기를 만들지 못한 구독은 토픽·요소·원인을 담은 ERROR 로그를
	 * 남기고 결과에서 제외한다.
	 *
	 * @param mappings			구독 설정 목록.
	 * @param updaterFactory	요소 위치로부터 값 갱신기를 만드는 함수. 실패하면 예외를 던진다.
	 * @param workers			반영 작업을 실행할 executor.
	 * @return	만들어진 구독자 목록(설정 순서 유지).
	 */
	static List<MqttSubscriber> createSubscribers(List<MqttElementSubscriber> mappings,
												Function<ElementLocation,ElementValueUpdater> updaterFactory,
												Executor workers) {
		List<MqttSubscriber> subscribers = Lists.newArrayList();
		for ( MqttElementSubscriber mapping: mappings ) {
			try {
				ElementValueUpdater updater = updaterFactory.apply(mapping.getElementLocation());
				subscribers.add(new MqttSubscriber(mapping, updater, workers, MqttSubscriber.DEFAULT_CAPACITY));
			}
			catch ( RuntimeException e ) {
				s_logger.error("skipping MQTT subscription, target element is not available: topic={}, element={}, "
								+ "cause={}", mapping.getTopic(), mapping.getElementLocationExpr(), ""+e);
			}
		}
		return subscribers;
	}

    @Override
    public void stop() {
    	if ( m_lease == null ) {
    		return;
    	}

    	// 공유 연결은 다른 사용자가 계속 사용할 수 있으므로, 이 endpoint의 구독을 먼저 해제한다.
    	for ( KeyValue<String,MqttSubscriber> subscription: m_subscriptions ) {
    		m_lease.getService().unsubscribe(subscription.key(), subscription.value());
    		// 아직 반영하지 않은 메시지는 버린다. 반영 중인 메시지는 아래에서 끝나기를 기다린다.
    		subscription.value().close();
    	}
    	m_subscriptions.clear();

    	m_workers.shutdown();
    	try {
    		if ( !m_workers.awaitTermination(WORKER_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) ) {
    			s_logger.warn("pending MQTT element updates did not finish within {}: {}", WORKER_STOP_TIMEOUT, this);
    		}
    	}
    	catch ( InterruptedException e ) {
    		Thread.currentThread().interrupt();
    	}
    	m_workers = null;

    	m_lease.close();
    	m_lease = null;
    }
	
	@Override
	public String toString() {
		return "MqttEndpoint[broker=" + m_brokerConfig.getBrokerUrl() + "]";
	}
}
