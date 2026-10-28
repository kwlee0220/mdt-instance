package mdt.endpoint.mqtt;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.concurrent.GuardedBy;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.model.ServiceSpecificationProfile;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.PropertyValue;
import de.fraunhofer.iosb.ilt.faaast.service.starter.InitializationException;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import mdt.ElementLocation;
import mdt.ElementValueUpdater;
import mdt.FaaastRuntime;
import mdt.MqttServicePool;
import mdt.client.support.MqttBrokerConfig;
import mdt.client.support.MqttService;
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
	// 시작한 뒤 보유하는 자원. start()가 끝까지 성공했을 때만 설정되고, stop()에서 정리된다.
	@GuardedBy("this") @Nullable private Session m_session;
	
	/**
	 * 한 번의 시작으로 만든 자원.
	 *
	 * @param workers		메시지를 요소에 반영하는 작업 쓰레드. 구독이 하나도 없으면 {@code null}.
	 * @param lease			공유 broker 연결 사용권. 구독이 하나도 없으면 {@code null}.
	 * @param subscribers	구독자.
	 * @param subscriptions	broker 구독 핸들.
	 */
	private record Session(@Nullable ExecutorService workers, @Nullable MqttServicePool.Lease lease,
							List<MqttSubscriber> subscribers, List<MqttService.Subscription> subscriptions) { }
	
	/**
	 * 정리 결과.
	 *
	 * @param unsubscribeFailures	broker 구독 해제를 확인하지 못한 구독 수(실패 또는 제한 시간 초과).
	 * @param workersTimedOut		반영 중인 작업이 제한 시간 안에 끝나지 않아 작업 쓰레드를 인터럽트했는지 여부.
	 */
	record StopResult(int unsubscribeFailures, boolean workersTimedOut) {
		boolean isClean() {
			return unsubscribeFailures == 0 && !workersTimedOut;
		}
	}

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
	 * <p>
	 * 시작 중에 실패하면(연결 사용권 획득, broker 구독 등록 등) 그때까지 만든 자원을 모두 정리하고 원인을 담은
	 * {@link EndpointException}을 던진다. 이미 시작된 상태에서 다시 호출하면 {@link IllegalStateException}을 던진다.
	 * {@link #stop()} 뒤에는 다시 시작할 수 있다.
	 */
    @Override
    public synchronized void start() throws EndpointException {
		if ( m_session != null ) {
			throw new IllegalStateException("MqttEndpoint is already started: " + this);
		}
		
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
		startSession(subscribers, workers);
    }
	
	/**
	 * 만들어진 구독자로 broker 연결을 얻고 구독을 등록하여 시작 상태로 만든다. 실패하면 받은 자원까지 모두 정리한다.
	 *
	 * @param subscribers	구독자. 비어 있으면 broker에 연결하지 않는다.
	 * @param workers		구독자가 반영 작업에 쓰는 작업 쓰레드.
	 * @throws EndpointException	연결 사용권 획득이나 구독 등록이 실패한 경우.
	 * @throws IllegalStateException	이미 시작된 경우.
	 */
	synchronized void startSession(List<MqttSubscriber> subscribers, ExecutorService workers) throws EndpointException {
		if ( m_session != null ) {
			workers.shutdown();
			throw new IllegalStateException("MqttEndpoint is already started: " + this);
		}
		if ( subscribers.isEmpty() ) {
			workers.shutdown();
			m_session = new Session(null, null, List.of(), List.of());
			return;
		}

		// 같은 broker를 사용하는 MQTT AssetVariable들과 연결을 공유한다.
		// 공유 연결이 이미 맺어져 있을 수 있으므로, 갱신기를 모두 만든 뒤에 구독한다.
		MqttServicePool.Lease lease = null;
		List<MqttService.Subscription> subscriptions = Lists.newArrayList();
		try {
			lease = MqttServicePool.acquire(m_brokerConfig);
			for ( MqttSubscriber subscriber: subscribers ) {
				subscriptions.add(lease.getService().subscribe(subscriber.getTopic(), subscriber.getQos(), subscriber));
			}
		}
		catch ( RuntimeException e ) {
			release(new Session(workers, lease, subscribers, subscriptions), WORKER_STOP_TIMEOUT);
			throw new EndpointException("failed to start " + this + ": " + e, e);
		}
		m_session = new Session(workers, lease, subscribers, subscriptions);
    }
	
	/**
	 * 시작된 상태인지 반환한다.
	 *
	 * @return	{@link #start()}가 성공한 뒤 {@link #stop()}되지 않았으면 {@code true}.
	 */
	public synchronized boolean isStarted() {
		return m_session != null;
	}
	
	/**
	 * 구독 설정마다 값 갱신기를 만들어 구독자를 생성한다.
	 * <p>
	 * 대상 요소 위치를 해석하지 못하거나 요소가 없어 갱신기를 만들지 못한 구독, 그리고 {@link PayloadFormat#TEXT}인데
	 * 대상이 Property가 아닌 구독은 토픽·요소·원인을 담은 ERROR 로그를 남기고 결과에서 제외한다.
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
			if ( mapping == null ) {
				s_logger.error("skipping a null MQTT subscription entry");
				continue;
			}
			try {
				ElementValueUpdater updater = updaterFactory.apply(mapping.getElementLocation());
				if ( mapping.getFormat() == PayloadFormat.TEXT
					&& !PropertyValue.class.isAssignableFrom(updater.getValueType()) ) {
					throw new IllegalArgumentException("format 'text' requires a Property, but the element value type is "
														+ updater.getValueType().getSimpleName());
				}
				subscribers.add(new MqttSubscriber(mapping, updater, workers));
			}
			catch ( RuntimeException e ) {
				s_logger.error("skipping MQTT subscription, cannot apply messages to the target element: topic={}, "
								+ "element={}, cause={}", mapping.getTopic(), mapping.getElementLocationExpr(), ""+e);
			}
		}
		return subscribers;
	}

	/**
	 * 구독을 멈추고 시작할 때 만든 자원을 정리한다. 시작되지 않았으면 아무것도 하지 않으며, 여러 번 호출해도 된다.
	 * <p>
	 * 정리는 다음 순서로 하며, 한 단계가 실패해도 다음 단계를 계속한다.
	 * <ol>
	 *   <li>구독자를 닫는다. 새 메시지를 받지 않고 대기 중인 메시지는 버리며, 상한 때문에 기다리던 MQTT 콜백 쓰레드를 깨운다.
	 *       공유 연결의 콜백이 풀려야 broker의 구독 해제 응답도 읽을 수 있다.</li>
	 *   <li>broker 구독을 해제한다. 구독마다 공유 서비스의 작업 제한 시간({@link MqttService#getOperationTimeout()})까지만 기다린다.</li>
	 *   <li>반영 중인 작업이 끝나기를 최대 5초 기다리고, 넘기면 작업 쓰레드를 인터럽트한다.</li>
	 *   <li>공유 연결 사용권을 반납한다.</li>
	 * </ol>
	 * 정리하지 못한 것이 있으면 WARN 로그로 알린다.
	 */
    @Override
    public void stop() {
		StopResult result = stop(WORKER_STOP_TIMEOUT);
		if ( result != null && !result.isClean() ) {
			s_logger.warn("{} stopped with leftovers: unconfirmed unsubscriptions={}, updates interrupted after {}={}",
							this, result.unsubscribeFailures(), WORKER_STOP_TIMEOUT, result.workersTimedOut());
		}
    }
	
	/**
	 * {@link #stop()}과 같고, 반영 작업을 기다리는 시간을 지정하고 정리 결과를 돌려준다.
	 *
	 * @param workerTimeout	반영 중인 작업을 기다리는 시간.
	 * @return	정리 결과. 시작되지 않았으면 {@code null}.
	 */
	synchronized @Nullable StopResult stop(Duration workerTimeout) {
		Session session = m_session;
		if ( session == null ) {
			return null;
		}
		m_session = null;
		return release(session, workerTimeout);
	}
	
	private StopResult release(Session session, Duration workerTimeout) {
		// 1. 새 메시지를 받지 않게 하고, 자리를 기다리는 콜백 쓰레드를 깨운다.
		session.subscribers().forEach(MqttSubscriber::close);
		
		// 2. broker 구독을 해제한다. 공유 연결은 다른 사용자가 계속 사용할 수 있으므로 이 엔드포인트의 구독만 해제한다.
		int unsubscribeFailures = 0;
		for ( MqttService.Subscription subscription: session.subscriptions() ) {
			try {
				if ( !subscription.unsubscribe() ) {
					++unsubscribeFailures;
				}
			}
			catch ( RuntimeException e ) {
				++unsubscribeFailures;
				s_logger.warn("failed to unsubscribe {}: cause={}", subscription, ""+e);
			}
		}
		
		// 3. 반영 중인 작업이 끝나기를 기다린다. 대기 중인 메시지는 1에서 이미 버렸다.
		boolean workersTimedOut = false;
		ExecutorService workers = session.workers();
		if ( workers != null ) {
			workers.shutdown();
			try {
				if ( !workers.awaitTermination(workerTimeout.toMillis(), TimeUnit.MILLISECONDS) ) {
					workersTimedOut = true;
					workers.shutdownNow();
				}
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				workersTimedOut = true;
				workers.shutdownNow();
			}
		}
		
		// 4. 공유 연결 사용권을 반납한다.
		if ( session.lease() != null ) {
			try {
				session.lease().close();
			}
			catch ( RuntimeException e ) {
				s_logger.warn("failed to release the shared MQTT connection: {}, cause={}", session.lease(), ""+e);
			}
		}
		
		return new StopResult(unsubscribeFailures, workersTimedOut);
	}
	
	@Override
	public String toString() {
		return "MqttEndpoint[broker=" + m_brokerConfig.getBrokerUrl() + "]";
	}
}
