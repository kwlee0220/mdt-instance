package mdt.endpoint.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.MoreExecutors;

import utils.Preconditions;

import mdt.ElementUpdateException;
import mdt.ElementValueUpdater;
import mdt.client.support.MqttService;


/**
 * MQTT 메시지의 payload를 AAS 요소 값으로 반영하는 구독자.
 * <p>
 * payload는 UTF-8 문자열로 읽은 AAS value-only JSON이며, {@link ElementValueUpdater}로 요소에 반영한다.
 * 갱신기는 {@link MqttEndpoint#start()}에서 대상 요소를 확인하며 만들어 넘겨준다.
 * <p>
 * {@link #onMessage}는 MQTT 콜백 쓰레드에서 호출되며, 반영 작업을 작업 쓰레드로 넘기고 곧바로 반환한다.
 * broker 연결은 MQTT 자산 변수와 공유되므로, 느린 반영(예: JDBC 저장)이 같은 연결의 다른 구독 수신을 막지 않게 하기 위함이다.
 * 반영은 구독별로 받은 순서대로 하나씩 수행하며, 반영 방식은 {@link DeliveryMode}를 따른다.
 * <ul>
 *   <li>{@link DeliveryMode#ALL}: 모든 메시지를 반영한다. 처리 중인 메시지와 대기 중인 메시지를 합한 수가 상한
 *       ({@code maxInFlightMessages})에 이르면 {@link OverflowPolicy}에 따라 자리가 날 때까지 콜백 쓰레드가 기다리거나
 *       ({@code wait}, 기본값) 새 메시지를 버린다({@code drop}).</li>
 *   <li>{@link DeliveryMode#LATEST}: 반영을 기다리는 값은 하나만 둔다. 새 메시지가 오면 아직 반영하지 못한 값을 버린다.</li>
 * </ul>
 * 반영에 실패하면 토픽과 대상 요소를 담은 로그를 남기고 다음 메시지를 계속 처리한다. 장치가 잘못된 형식을 계속 보내는 경우
 * 로그가 넘치지 않도록, 연속 실패는 다음과 같이 남긴다.
 * <ul>
 *   <li>연속 실패의 첫 번째: ERROR</li>
 *   <li>연속 10번째, 100번째, 1000번째 …: 연속 실패 횟수와 마지막 원인을 담은 ERROR</li>
 *   <li>그 밖의 실패: DEBUG</li>
 *   <li>실패가 이어지다 성공하면: 연속 실패 횟수를 담은 INFO (이후 실패는 다시 첫 번째로 센다)</li>
 * </ul>
 *
 * @author Kang-Woo Lee (ETRI)
 */
class MqttSubscriber implements MqttService.Subscriber {
	private static final Logger s_logger = LoggerFactory.getLogger(MqttEndpoint.class);
	/** {@link DeliveryMode#ALL}에서 처리 중·대기 중인 메시지 수 상한의 기본값. */
	static final int DEFAULT_CAPACITY = MqttElementSubscriber.DEFAULT_MAX_IN_FLIGHT_MESSAGES;

	private record Pending(String topic, String json) { }

	private final MqttElementSubscriber m_mapping;
	private final ElementValueUpdater m_updater;
	private final Executor m_sequential;		// 구독별로 작업을 순서대로 실행한다.
	private final int m_capacity;
	private final Semaphore m_slots;			// ALL: 처리 중·대기 중인 메시지가 쓸 수 있는 남은 자리
	private final AtomicReference<Pending> m_latest = new AtomicReference<>();	// LATEST: 반영을 기다리는 값
	private final AtomicLong m_dropped = new AtomicLong();
	private volatile boolean m_closed = false;
	private volatile boolean m_fullWarned = false;
	// 연속 실패 횟수. 반영 작업은 구독별로 한 번에 하나씩 실행되므로 동시에 갱신되지 않는다.
	private volatile long m_consecutiveFailures = 0;

	/**
	 * 구독자를 만든다. 처리 중 메시지 상한은 구독 설정의 {@code maxInFlightMessages}를 쓴다.
	 *
	 * @param mapping	토픽, 대상 요소, 반영 방식 등 구독 설정.
	 * @param updater	대상 요소의 값 갱신기.
	 * @param workers	반영 작업을 실행할 executor. 구독자는 이를 감싸 자신의 작업을 순서대로 실행한다.
	 */
	MqttSubscriber(MqttElementSubscriber mapping, ElementValueUpdater updater, Executor workers) {
		this(mapping, updater, workers, mapping.getMaxInFlightMessages());
	}

	/**
	 * 구독자를 만든다.
	 *
	 * @param mapping	토픽, 대상 요소, 반영 방식 등 구독 설정.
	 * @param updater	대상 요소의 값 갱신기.
	 * @param workers	반영 작업을 실행할 executor. 구독자는 이를 감싸 자신의 작업을 순서대로 실행한다.
	 * @param capacity	{@link DeliveryMode#ALL}에서 처리 중·대기 중인 메시지 수의 상한.
	 */
	MqttSubscriber(MqttElementSubscriber mapping, ElementValueUpdater updater, Executor workers, int capacity) {
		Preconditions.checkNotNullArgument(mapping, "mapping is null");
		Preconditions.checkNotNullArgument(updater, "updater is null");
		Preconditions.checkNotNullArgument(workers, "workers is null");
		Preconditions.checkArgument(capacity > 0, "capacity must be positive: %s", capacity);

		m_mapping = mapping;
		m_updater = updater;
		m_sequential = MoreExecutors.newSequentialExecutor(workers);
		m_capacity = capacity;
		m_slots = new Semaphore(capacity);
	}

	String getTopic() {
		return m_mapping.getTopic();
	}
	
	int getQos() {
		return m_mapping.getQos();
	}

	/**
	 * 반영하지 못하고 버린 메시지 수를 반환한다. {@link DeliveryMode#LATEST}에서 새 값으로 대체된 메시지와,
	 * {@link OverflowPolicy#DROP}에서 상한 때문에 버린 메시지를 센다.
	 *
	 * @return	버린 메시지 수.
	 */
	long getDroppedCount() {
		return m_dropped.get();
	}
	
	/**
	 * 현재까지 이어진 연속 반영 실패 횟수를 반환한다. 반영에 성공하면 0이 된다.
	 *
	 * @return	연속 실패 횟수.
	 */
	long getConsecutiveFailureCount() {
		return m_consecutiveFailures;
	}

	/**
	 * 새 메시지를 더 이상 받지 않고, 아직 반영하지 않은 메시지는 버린다. 이미 반영 중인 메시지는 끝까지 처리된다.
	 * <p>
	 * 상한 때문에 자리를 기다리던 콜백 쓰레드도 깨운다. 깨어난 콜백은 닫힌 것을 확인하고 메시지를 버린다.
	 */
	void close() {
		m_closed = true;
		m_latest.set(null);
		// 기다리는 콜백을 깨운다. 닫힌 뒤에는 자리 수를 쓰지 않으므로 남는 permit은 의미가 없다.
		m_slots.release(m_capacity);
	}
	
	boolean isClosed() {
		return m_closed;
	}

	@Override
	public void onMessage(String topic, MqttMessage message) {
		if ( m_closed ) {
			return;
		}

		String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
		Pending pending = new Pending(topic, m_mapping.getFormat().toValueJson(payload));
		switch ( m_mapping.getDelivery() ) {
			case LATEST -> {
				// 반영을 기다리는 값이 없었을 때만 반영 작업을 예약한다. 있었으면 그 값을 새 값으로 바꾼다(이전 값은 버림).
				if ( m_latest.getAndSet(pending) == null ) {
					try {
						m_sequential.execute(this::applyLatest);
					}
					catch ( RejectedExecutionException e ) {
						// 작업 쓰레드가 이미 정리된 경우(종료 중). 예약하지 못한 값은 버린다.
						m_latest.compareAndSet(pending, null);
					}
				}
				else {
					m_dropped.incrementAndGet();
				}
			}
			case ALL -> {
				if ( !acquireSlot() ) {
					return;
				}
				try {
					m_sequential.execute(() -> {
						try {
							apply(pending);
						}
						finally {
							m_slots.release();
						}
					});
				}
				catch ( RejectedExecutionException e ) {
					// 작업이 실행되지 않으므로 작업 안의 finally도 실행되지 않는다. 잡아 둔 자리를 여기서 돌려준다.
					m_slots.release();
				}
			}
		}
	}

	private boolean acquireSlot() {
		if ( m_slots.tryAcquire() ) {
			return checkOpenAfterAcquire();
		}
		
		if ( m_mapping.getOverflowPolicy() == OverflowPolicy.DROP ) {
			long dropped = m_dropped.incrementAndGet();
			if ( dropped == 1 || isReminderCount(dropped) ) {
				s_logger.warn("MQTT in-flight limit ({}) reached, dropped {} message(s) so far: topic={}, element={}",
								m_capacity, dropped, m_mapping.getTopic(), m_mapping.getElementLocationExpr());
			}
			return false;
		}
		
		if ( !m_fullWarned ) {
			m_fullWarned = true;
			s_logger.warn("MQTT in-flight limit ({}) reached, waiting for pending updates: topic={}, element={}",
							m_capacity, m_mapping.getTopic(), m_mapping.getElementLocationExpr());
		}
		try {
			m_slots.acquire();
			return checkOpenAfterAcquire();
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			s_logger.warn("interrupted while waiting for a backlog slot, dropping a message: topic={}, element={}",
							m_mapping.getTopic(), m_mapping.getElementLocationExpr());
			return false;
		}
	}
	
	// 자리를 얻은 뒤 그사이 닫혔으면 자리를 돌려주고 메시지를 버린다(close()가 깨운 경우 포함).
	private boolean checkOpenAfterAcquire() {
		if ( m_closed ) {
			m_slots.release();
			return false;
		}
		return true;
	}
	
	private void applyLatest() {
		Pending pending = m_latest.getAndSet(null);
		if ( pending != null ) {
			apply(pending);
		}
	}

	private void apply(Pending pending) {
		if ( m_closed ) {
			return;
		}
		try {
			m_updater.update(pending.json());
		}
		catch ( ElementUpdateException e ) {
			onFailed(pending.topic(), e.getMessage(), null);
			return;
		}
		catch ( RuntimeException e ) {
			// 작업 쓰레드에서 난 예외는 다른 곳에서 기록되지 않으므로 여기서 남긴다.
			onFailed(pending.topic(), "" + e, e);
			return;
		}
		
		long failures = m_consecutiveFailures;
		if ( failures > 0 ) {
			m_consecutiveFailures = 0;
			s_logger.info("MQTT element updates recovered after {} consecutive failures: topic={}, element={}",
							failures, pending.topic(), m_mapping.getElementLocationExpr());
		}
	}
	
	// payload에는 비밀 값이 있을 수 있으므로 로그에 남기지 않는다.
	private void onFailed(String topic, String cause, @Nullable Throwable unexpected) {
		long failures = ++m_consecutiveFailures;
		String element = m_mapping.getElementLocationExpr();
		if ( failures == 1 ) {
			if ( unexpected != null ) {
				s_logger.error("unexpected failure while updating element from MQTT message: topic={}, element={}",
								topic, element, unexpected);
			}
			else {
				s_logger.error("failed to update element from MQTT message: topic={}, element={}, cause={}",
								topic, element, cause);
			}
		}
		else if ( isReminderCount(failures) ) {
			s_logger.error("still failing to update element from MQTT messages: {} consecutive failures, "
							+ "topic={}, element={}, last cause={}", failures, topic, element, cause);
		}
		else if ( s_logger.isDebugEnabled() ) {
			s_logger.debug("failed to update element from MQTT message ({} consecutive failures): topic={}, "
							+ "element={}, cause={}", failures, topic, element, cause);
		}
	}
	
	// 10, 100, 1000, … 인지 확인한다.
	static boolean isReminderCount(long count) {
		if ( count < 10 ) {
			return false;
		}
		while ( count % 10 == 0 ) {
			count /= 10;
		}
		return count == 1;
	}

	@Override
	public String toString() {
		return String.format("MqttSubscriber[%s -> %s, delivery=%s]", m_mapping.getTopic(),
								m_mapping.getElementLocationExpr(), m_mapping.getDelivery().toJsonValue());
	}
}
