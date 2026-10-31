package mdt.ext.rck;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * RCK 시뮬레이션이 쓰는 전용 스레드들.
 * <p>
 * 타이머 신호나 전송 실패 신호를 공용 스레드(utils {@code Executions.getExecutor()}, 공용 {@code ForkJoinPool},
 * JDK 내부 스레드)에서 바로 보내면, 상태 차트가 그 스레드에서 전이를 실행한다(한가한 차트는 신호를 보낸 스레드가
 * 처리한다). 전이에는 중지 명령 전송 대기(최대 2초)와 차트 lock이 들어 있어 공용 스레드를 붙잡게 되므로, RCK의 작업은
 * 모두 여기 스레드에서 실행한다. 모든 스레드는 데몬이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
final class RCKExecutors {
	/**
	 * 시간 예약만 하는 스레드. 예약된 작업은 바로 {@link #WORKERS}로 넘기므로 여기서는 막히지 않는다.
	 * 응답 대기 타이머는 메시지마다 취소하고 다시 걸므로, 취소된 작업이 원래 실행 시각까지 큐에 남지 않게 한다.
	 */
	private static final ScheduledExecutorService TIMER = newTimer();
	/** 신호 전달, 취소, MDT 출력 갱신 같은 작업을 실행하는 스레드 풀. */
	static final Executor WORKERS = newWorkers();

	private RCKExecutors() {
		throw new AssertionError("Should not be called: class=" + getClass());
	}

	/**
	 * 주어진 시간이 지나면 작업을 {@link #WORKERS}에서 실행한다.
	 *
	 * @param task	실행할 작업
	 * @param delay	지연 시간
	 * @return	예약을 취소할 수 있는 핸들. 작업이 아직 넘겨지지 않았으면 {@code cancel}로 막을 수 있다.
	 */
	static ScheduledFuture<?> schedule(Runnable task, Duration delay) {
		return TIMER.schedule(() -> WORKERS.execute(task), delay.toMillis(), TimeUnit.MILLISECONDS);
	}

	private static ScheduledExecutorService newTimer() {
		ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, daemonThreads("rck-timer"));
		timer.setRemoveOnCancelPolicy(true);
		return timer;
	}

	private static ExecutorService newWorkers() {
		return Executors.newCachedThreadPool(daemonThreads("rck-worker"));
	}

	private static ThreadFactory daemonThreads(String prefix) {
		AtomicInteger seq = new AtomicInteger();
		return task -> {
			Thread thread = new Thread(task, prefix + "-" + seq.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
