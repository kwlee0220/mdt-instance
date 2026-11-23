package mdt.assetconnection.operation;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Preconditions;
import utils.func.CheckedRunnable;


/**
 * 비동기로 호출된 AAS 연산 하나의 실행. 실행 스레드를 인터럽트하여 취소할 수 있다.
 * <p>
 * 실행 상태는 {@link State#RUNNING}에서 시작하여 다음과 같이 바뀐다.
 * <ul>
 *   <li>작업이 정상 종료하면 {@link State#COMPLETED}가 되고 성공 처리기를 부른다.</li>
 *   <li>작업이 예외로 끝나면 {@link State#FAILED}가 되고 실패 처리기를 부른다.</li>
 *   <li>{@link #cancel()}이 받아들여지면 {@link State#CANCELLING}이 되고 실행 스레드를 인터럽트한다. 이후 작업이
 *       어떻게 끝나든 {@link State#CANCELLED}가 되고 취소 처리기만 부른다. 성공·실패 처리기는 부르지 않는다.</li>
 * </ul>
 * 상태 전이는 원자적이므로 처리기는 셋 중 정확히 하나만 한 번 불린다. 처리기가 던진 예외는 로그만 남긴다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class OperationExecution {
	private static final Logger s_logger = LoggerFactory.getLogger(OperationExecution.class);

	public enum State {
		RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED;

		public boolean isFinished() {
			return this == COMPLETED || this == FAILED || this == CANCELLED;
		}
	}

	/** 취소 처리기(FA³ST에 {@code CANCELED} 저장)의 상태. */
	public enum CancellationHandlerState {
		/** 아직 부르지 않았다(실행이 취소로 끝나지 않았다). */
		NOT_RUN,
		/** 실행 중이다(처음 시도이거나 재시도). */
		RUNNING,
		/** 성공했다. */
		SUCCEEDED,
		/** 실패했다. {@link OperationExecution#retryCancellationHandler(Duration)}로 다시 부를 수 있다. */
		FAILED,
	}

	private final String m_handleId;
	private final String m_operationName;
	private final CheckedRunnable m_work;
	private final Runnable m_onCompleted;
	private final Consumer<Throwable> m_onFailed;
	private final CheckedRunnable m_onCancelled;

	private final AtomicReference<State> m_state = new AtomicReference<>(State.RUNNING);
	private final CountDownLatch m_finished = new CountDownLatch(1);
	private volatile Instant m_finishTime;

	private final Object m_lock = new Object();
	@GuardedBy("m_lock") private Thread m_worker;
	// 취소 처리기(CANCELED 저장)의 상태, 마지막 실패 원인, 현재(또는 마지막) 시도의 완료 신호.
	// 상태만 잠금 안에서 바꾸고, 처리기 자체는 잠금 밖에서 실행한다(느린 저장이 상태 조회를 막지 않게).
	@GuardedBy("m_lock") private CancellationHandlerState m_handlerState = CancellationHandlerState.NOT_RUN;
	@GuardedBy("m_lock") private Throwable m_cancellationHandlerFailure;
	@GuardedBy("m_lock") private CountDownLatch m_handlerDone;
	// 보존 시간의 기준 시각: 실행이 끝난 시각, 이후 취소 처리기 시도가 끝날 때마다 갱신된다.
	@GuardedBy("m_lock") private Instant m_settledTime;
	// 실행 목록에서 만료되어 지워졌는지 여부. 한번 켜지면 꺼지지 않는다.
	@GuardedBy("m_lock") private boolean m_expired = false;
	// 처리기 재시도를 실행할 executor. start()에서 정해진다.
	private volatile Executor m_executor;

	/**
	 * 실행을 만든다. 작업은 {@link #start(Executor)}를 호출해야 시작한다.
	 *
	 * @param handleId		FA³ST 실행 ID. 가로채지 못했으면 {@code null}.
	 * @param operationName	로그에 쓸 연산 이름.
	 * @param work			실행할 작업.
	 * @param onCompleted	작업이 정상 종료했을 때 부를 처리기.
	 * @param onFailed		작업이 예외로 끝났을 때 그 예외로 부를 처리기.
	 * @param onCancelled	취소된 실행이 끝났을 때 부를 처리기. 실패하면 그 원인이 기록되고
	 * 						{@link #retryCancellationHandler(Duration)}로 다시 부를 수 있다.
	 */
	public OperationExecution(String handleId, String operationName,
								CheckedRunnable work,
								Runnable onCompleted,
								Consumer<Throwable> onFailed,
								CheckedRunnable onCancelled) {
		Preconditions.checkNotNullArgument(work, "work");
		Preconditions.checkNotNullArgument(onCompleted, "onCompleted");
		Preconditions.checkNotNullArgument(onFailed, "onFailed");
		Preconditions.checkNotNullArgument(onCancelled, "onCancelled");

		m_handleId = handleId;
		m_operationName = operationName;
		m_work = work;
		m_onCompleted = onCompleted;
		m_onFailed = onFailed;
		m_onCancelled = onCancelled;
	}

	public String getHandleId() {
		return m_handleId;
	}

	public String getOperationName() {
		return m_operationName;
	}

	public State getState() {
		return m_state.get();
	}

	/**
	 * 실행이 끝난 시각. 아직 끝나지 않았으면 {@code null}.
	 * <p>
	 * 처리기 호출이 모두 끝난 뒤에 정해진다.
	 */
	public Instant getFinishTime() {
		return m_finishTime;
	}

	/**
	 * 보존 시간이 지났으면 만료로 표시한다. 실행 목록이 항목을 지우기 전에 부른다.
	 * <p>
	 * 보존 시간의 기준은 마지막으로 끝난 시각이다. 실행이 끝난 시각에서 시작해, 취소 처리기(CANCELED 저장)의 시도가 끝날
	 * 때마다 갱신된다. 아직 끝나지 않은 실행과 취소 처리기가 실행 중인 실행은 만료되지 않는다. 만료 판단과 재시도 시작은 같은
	 * 잠금 안에서 일어나므로, 만료된 실행에서 재시도가 시작되거나 재시도 중인 실행이 만료되는 일은 없다.
	 *
	 * @param threshold	이 시각보다 먼저 끝났으면 만료로 본다.
	 * @return	만료되었으면(이미 만료되었던 경우 포함) {@code true}.
	 */
	public boolean tryExpire(Instant threshold) {
		synchronized ( m_lock ) {
			if ( m_expired ) {
				return true;
			}
			if ( m_finishTime == null || m_settledTime == null
				|| m_handlerState == CancellationHandlerState.RUNNING ) {
				return false;
			}
			if ( m_settledTime.isBefore(threshold) ) {
				m_expired = true;
			}
			return m_expired;
		}
	}

	/**
	 * 실행 목록에서 만료되었는지 여부를 반환한다. 만료된 실행에는 취소 처리기 재시도가 시작되지 않는다.
	 *
	 * @return	만료되었으면 {@code true}.
	 */
	public boolean isExpired() {
		synchronized ( m_lock ) {
			return m_expired;
		}
	}

	/**
	 * 취소 처리기가 실패한 원인을 반환한다.
	 * <p>
	 * 취소된 실행은 처리기가 실패해도 {@link State#CANCELLED}로 끝나므로, 처리기가 맡은 일(FA³ST에 {@code CANCELED}
	 * 저장)이 이루어졌는지는 이 값으로 확인한다.
	 *
	 * @return	실패 원인. 처리기가 성공했거나 아직 불리지 않았으면 {@code null}.
	 */
	public Throwable getCancellationHandlerFailure() {
		synchronized ( m_lock ) {
			return m_cancellationHandlerFailure;
		}
	}

	/**
	 * 취소 처리기의 상태를 반환한다. 처리기가 실행 중이어도 기다리지 않는다.
	 * <p>
	 * 실행이 {@link State#CANCELLED}로 바뀌는 일과 처리기 상태를 {@link CancellationHandlerState#RUNNING}으로 바꾸는 일은
	 * 같은 잠금 안에서 일어나므로, "취소로 끝났지만 처리기는 아직 부르지 않음"인 상태는 보이지 않는다.
	 *
	 * @return	처리기 상태.
	 */
	public CancellationHandlerState getCancellationHandlerState() {
		synchronized ( m_lock ) {
			return m_handlerState;
		}
	}

	/**
	 * 실패했던 취소 처리기를 다시 부르고, 끝나기를 최대 {@code wait}만큼 기다린다.
	 * <p>
	 * 처리기는 이 메소드를 부른 스레드가 아니라 실행의 executor에서 실행한다. 이미 재시도가 실행 중이면 새로 시작하지 않고
	 * 그 시도를 함께 기다린다. 실행 목록에서 만료된 실행이면 재시도를 시작하지 않는다({@link #isExpired()}). 처리기를 아직 부르지 않았으면(실행이 취소로 끝나지 않았으면) 부르지 않고
	 * {@link CancellationHandlerState#NOT_RUN}을 반환하며, 이를 성공으로 보면 안 된다.
	 *
	 * @param wait	처리기가 끝나기를 기다리는 최대 시간.
	 * @return	기다린 뒤의 처리기 상태. 시간 안에 끝나지 않았으면 {@link CancellationHandlerState#RUNNING}.
	 * @throws InterruptedException	기다리는 중에 인터럽트된 경우.
	 */
	public CancellationHandlerState retryCancellationHandler(Duration wait) throws InterruptedException {
		CountDownLatch done;
		boolean startNew = false;
		synchronized ( m_lock ) {
			if ( m_expired ) {
				// 실행 목록에서 지워진 실행이다. 결과를 확인할 수 없으므로 재시도를 시작하지 않는다.
				return m_handlerState;
			}
			switch ( m_handlerState ) {
				case NOT_RUN:
				case SUCCEEDED:
					return m_handlerState;
				case RUNNING:
					done = m_handlerDone;
					break;
				case FAILED:
				default:
					done = beginCancellationHandler();
					startNew = true;
					break;
			}
		}
		if ( startNew ) {
			final CountDownLatch attemptDone = done;
			Runnable attempt = () -> runCancellationHandler(attemptDone);
			if ( !submit(attempt) ) {
				// 실행 목록의 스레드 풀이 닫혔으면(인스턴스 종료 중) 별도 스레드에서 실행한다.
				Thread thread = new Thread(attempt, "cancellation-handler-" + m_handleId);
				thread.setDaemon(true);
				thread.start();
			}
		}
		done.await(wait.toMillis(), TimeUnit.MILLISECONDS);
		return getCancellationHandlerState();
	}

	/**
	 * 주어진 executor에서 작업을 시작한다.
	 *
	 * @param executor	작업을 실행할 executor.
	 * @throws RejectedExecutionException	executor가 작업을 받지 않는 경우.
	 */
	public void start(Executor executor) {
		m_executor = executor;
		executor.execute(this::run);
	}

	/**
	 * 실행 취소를 요청한다.
	 * <p>
	 * 실행 중이면 상태를 {@link State#CANCELLING}으로 바꾸고 실행 스레드를 인터럽트한다. 작업이 인터럽트에
	 * 반응해 끝나면 {@link State#CANCELLED}가 된다. 반응하지 않는 작업은 끝날 때까지 {@code CANCELLING}으로
	 * 남는다. 끝나기를 기다리려면 {@link #awaitFinished(Duration)}를 쓴다.
	 *
	 * @return	취소가 받아들여졌으면(이미 취소 중이었던 경우 포함) {@code true}, 이미 끝났으면 {@code false}.
	 */
	public boolean cancel() {
		if ( m_state.compareAndSet(State.RUNNING, State.CANCELLING) ) {
			synchronized ( m_lock ) {
				if ( m_worker != null ) {
					m_worker.interrupt();
				}
			}
			s_logger.info("cancelling operation: op={}, handle={}", m_operationName, m_handleId);
			return true;
		}
		return m_state.get() == State.CANCELLING;
	}

	/**
	 * 실행이 끝나기를 기다린다.
	 *
	 * @param timeout	최대 대기 시간.
	 * @return	시간 안에 끝났으면 {@code true}.
	 * @throws InterruptedException	기다리는 중에 인터럽트된 경우.
	 */
	public boolean awaitFinished(Duration timeout) throws InterruptedException {
		return m_finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	private void run() {
		boolean started;
		synchronized ( m_lock ) {
			// 시작 전에 취소되었으면 작업을 수행하지 않는다.
			started = m_state.get() == State.RUNNING;
			if ( started ) {
				m_worker = Thread.currentThread();
			}
		}

		Throwable failure = null;
		if ( started ) {
			try {
				m_work.run();
			}
			catch ( Throwable e ) {
				failure = e;
			}
			finally {
				synchronized ( m_lock ) {
					m_worker = null;
					// 작업이 소비하지 않은 취소 인터럽트가 실행 스레드(풀 스레드)에 남지 않게 한다.
					Thread.interrupted();
				}
			}
		}

		try {
			finish(failure);
		}
		finally {
			Instant now = Instant.now();
			m_finishTime = now;
			synchronized ( m_lock ) {
				m_settledTime = now;
			}
			m_finished.countDown();
		}
		if ( failure instanceof Error error ) {
			throw error;
		}
	}

	private void finish(Throwable failure) {
		State target = (failure == null) ? State.COMPLETED : State.FAILED;
		if ( m_state.compareAndSet(State.RUNNING, target) ) {
			if ( failure == null ) {
				invoke("completion", m_onCompleted::run);
			}
			else {
				invoke("failure", () -> m_onFailed.accept(failure));
			}
		}
		else {
			// RUNNING이 아니면 취소가 받아들여진 상태(CANCELLING)이다. 작업 결과와 관계없이 취소로 끝낸다.
			if ( failure != null && s_logger.isDebugEnabled() ) {
				s_logger.debug("cancelled operation ended with: op={}, handle={}, cause={}",
								m_operationName, m_handleId, ""+failure);
			}
			// CANCELLED 전이와 처리기 상태(RUNNING) 표시를 같은 잠금 안에서 하여, "취소로 끝났지만 처리기는 아직"인 상태가
			// 처리기 상태를 읽는 쪽에 보이지 않게 한다. 처리기 자체는 잠금 밖에서 실행한다.
			CountDownLatch done;
			synchronized ( m_lock ) {
				m_state.set(State.CANCELLED);
				done = beginCancellationHandler();
			}
			runCancellationHandler(done);
		}
	}

	private boolean submit(Runnable task) {
		Executor executor = m_executor;
		if ( executor == null ) {
			return false;
		}
		try {
			executor.execute(task);
			return true;
		}
		catch ( RejectedExecutionException e ) {
			return false;
		}
	}

	@GuardedBy("m_lock")
	private CountDownLatch beginCancellationHandler() {
		m_handlerState = CancellationHandlerState.RUNNING;
		m_handlerDone = new CountDownLatch(1);
		return m_handlerDone;
	}

	/**
	 * 취소 처리기를 한 번 실행하고 결과를 기록한다. 잠금 밖에서 부른다.
	 */
	private void runCancellationHandler(CountDownLatch done) {
		try {
			m_onCancelled.run();
			synchronized ( m_lock ) {
				m_cancellationHandlerFailure = null;
				m_handlerState = CancellationHandlerState.SUCCEEDED;
				m_settledTime = Instant.now();
			}
		}
		catch ( Throwable e ) {
			synchronized ( m_lock ) {
				m_cancellationHandlerFailure = e;
				m_handlerState = CancellationHandlerState.FAILED;
				m_settledTime = Instant.now();
			}
			s_logger.error("cancellation handler failed: op={}, handle={}, cause={}",
							m_operationName, m_handleId, ""+e);
			if ( e instanceof Error error ) {
				throw error;
			}
		}
		finally {
			done.countDown();
		}
	}

	private void invoke(String kind, Runnable handler) {
		try {
			handler.run();
		}
		catch ( Exception e ) {
			s_logger.warn("{} handler failed: op={}, handle={}", kind, m_operationName, m_handleId, e);
		}
	}

	@Override
	public String toString() {
		return String.format("OperationExecution[op=%s, handle=%s, state=%s]",
								m_operationName, m_handleId, m_state.get());
	}
}
