package mdt.assetconnection.operation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Preconditions;
import utils.func.FOption;


/**
 * 비동기로 호출된 AAS 연산의 실행({@link OperationExecution})을 FA³ST 실행 ID로 찾을 수 있게 보관한다.
 * <p>
 * 인스턴스당 하나이며 {@code MDTService}가 갖는다. 연산 실행에 쓰는 전용 스레드 풀도 갖는다.
 * <p>
 * 끝난 실행은 보존 시간({@link #getRetainTimeout()}, 기본 30초) 동안 남겨 두어 그 사이의 취소 요청이 "이미 끝남"을
 * 알 수 있게 한다. 보존 시간은 마지막으로 끝난 시각(실행 종료, 이후 취소 처리기 재시도의 종료)부터 세고, 취소 처리기가
 * 실행 중인 항목은 지우지 않는다. 보존 시간이 지난 항목은 등록·조회할 때 지운다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationExecutionRegistry {
	private static final Logger s_logger = LoggerFactory.getLogger(OperationExecutionRegistry.class);

	public static final Duration DEFAULT_RETAIN_TIMEOUT = Duration.ofSeconds(30);
	/** {@link #shutdown(Duration)}의 기본 대기 시간. */
	public static final Duration DEFAULT_SHUTDOWN_WAIT = Duration.ofSeconds(2);

	private final Map<String,OperationExecution> m_executions = new ConcurrentHashMap<>();
	private final ExecutorService m_executor;
	private volatile Duration m_retainTimeout = DEFAULT_RETAIN_TIMEOUT;
	private final Object m_shutdownLock = new Object();
	@GuardedBy("m_shutdownLock") private boolean m_shutdown = false;

	public OperationExecutionRegistry() {
		m_executor = Executors.newCachedThreadPool(new DaemonThreadFactory("aas-operation-"));
	}

	/**
	 * 연산 실행에 쓰는 executor. 작업마다 스레드를 하나 쓰며, 작업이 끝난 스레드는 재사용한다.
	 */
	public Executor getExecutor() {
		return m_executor;
	}

	public Duration getRetainTimeout() {
		return m_retainTimeout;
	}

	public void setRetainTimeout(Duration timeout) {
		Preconditions.checkNotNullArgument(timeout, "retainTimeout");
		Preconditions.checkArgument(!timeout.isNegative(), "retainTimeout must not be negative: " + timeout);

		m_retainTimeout = timeout;
	}

	/**
	 * 실행을 등록한다. 같은 실행 ID의 항목이 있으면 바꾼다.
	 * <p>
	 * 등록과 {@link #shutdown}의 종료 표시는 같은 잠금 아래에서 일어난다. 그래서 종료가 시작되기 전에 등록된 실행은
	 * 모두 종료 때 취소되고, 종료가 시작된 뒤의 등록은 거부된다.
	 *
	 * @param exec	등록할 실행. 실행 ID가 있어야 한다.
	 * @throws IllegalStateException	실행 목록이 이미 종료 중이거나 종료된 경우.
	 */
	public void register(OperationExecution exec) {
		Preconditions.checkNotNullArgument(exec, "execution");
		Preconditions.checkArgument(exec.getHandleId() != null, "execution has no handle: " + exec);

		purgeExpired();
		synchronized ( m_shutdownLock ) {
			if ( m_shutdown ) {
				throw new IllegalStateException("operation execution registry is shut down: rejected " + exec);
			}
			m_executions.put(exec.getHandleId(), exec);
		}
	}

	/**
	 * 등록된 실행을 지운다. 시작하지 못한 실행을 되돌릴 때 쓴다.
	 */
	public void unregister(String handleId) {
		m_executions.remove(handleId);
	}

	/**
	 * 실행 ID로 실행을 찾는다. 보존 시간이 지난 항목은 찾지 못한다.
	 */
	public FOption<OperationExecution> find(String handleId) {
		purgeExpired();
		return FOption.ofNullable(m_executions.get(handleId));
	}

	/**
	 * 실행 중인 모든 실행의 취소를 요청한다.
	 */
	public void cancelAll() {
		for ( OperationExecution exec: m_executions.values() ) {
			exec.cancel();
		}
	}

	/**
	 * 실행 중인 모든 실행을 취소하고 실행 스레드 풀을 닫는다. 취소된 실행이 끝나기를 최대 {@code wait}만큼 기다린다.
	 * <p>
	 * 먼저 종료를 표시하여 새 등록을 거부하고, 그때까지 등록된 실행을 모두 취소한다. 등록은 되었지만 아직 시작하지
	 * 않은 실행은 시작해도 작업을 수행하지 않고 취소로 끝나거나, 닫힌 스레드 풀에 거부되어 시작 실패로 처리된다.
	 */
	public void shutdown(Duration wait) {
		List<OperationExecution> registered;
		synchronized ( m_shutdownLock ) {
			m_shutdown = true;
			registered = List.copyOf(m_executions.values());
		}
		for ( OperationExecution exec: registered ) {
			exec.cancel();
		}
		m_executor.shutdown();
		try {
			if ( !m_executor.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS) ) {
				s_logger.warn("some operations are still running after shutdown: wait={}", wait);
			}
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 보존 시간이 지난 실행을 지운다.
	 * <p>
	 * 만료 여부는 실행 객체가 자신의 잠금 안에서 판단한다({@link OperationExecution#tryExpire(Instant)}). 기준은 마지막으로
	 * 끝난 시각(실행 종료, 이후 취소 처리기 시도의 종료)이고, 취소 처리기가 실행 중인 실행은 지우지 않는다.
	 */
	private void purgeExpired() {
		Instant threshold = Instant.now().minus(m_retainTimeout);
		for ( Map.Entry<String,OperationExecution> entry: m_executions.entrySet() ) {
			if ( entry.getValue().tryExpire(threshold) ) {
				m_executions.remove(entry.getKey(), entry.getValue());
			}
		}
	}

	private static final class DaemonThreadFactory implements ThreadFactory {
		private final String m_prefix;
		private final AtomicInteger m_seq = new AtomicInteger();

		DaemonThreadFactory(String prefix) {
			m_prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable task) {
			Thread thread = new Thread(task, m_prefix + m_seq.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	}
}
