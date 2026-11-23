package mdt.assetconnection.operation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import utils.func.CheckedRunnable;

import mdt.assetconnection.operation.OperationExecution.State;


/**
 * {@link OperationExecution}의 상태 전이와 처리기 호출, {@link OperationExecutionRegistry}의 보관·정리 테스트.
 */
public class OperationExecutionTest {
	private static final Duration WAIT = Duration.ofSeconds(5);

	private ExecutorService m_executor;
	private final List<String> m_calls = new CopyOnWriteArrayList<>();
	private final AtomicReference<Throwable> m_failure = new AtomicReference<>();

	@Before
	public void setUp() {
		m_executor = Executors.newSingleThreadExecutor();
	}

	@After
	public void tearDown() {
		m_executor.shutdownNow();
	}

	private OperationExecution execution(CheckedRunnable work) {
		return new OperationExecution("h1", "op", work,
										() -> m_calls.add("completed"),
										e -> { m_failure.set(e); m_calls.add("failed"); },
										() -> m_calls.add("cancelled"));
	}

	@Test(timeout = 10000)
	public void normalCompletion() throws Exception {
		OperationExecution exec = execution(() -> { });
		exec.start(m_executor);

		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(State.COMPLETED, exec.getState());
		assertEquals(List.of("completed"), m_calls);
		assertNotNull(exec.getFinishTime());
		assertFalse(exec.cancel());
	}

	@Test(timeout = 10000)
	public void failure() throws Exception {
		IllegalStateException error = new IllegalStateException("boom");
		OperationExecution exec = execution(() -> { throw error; });
		exec.start(m_executor);

		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(State.FAILED, exec.getState());
		assertEquals(List.of("failed"), m_calls);
		assertSame(error, m_failure.get());
		assertFalse(exec.cancel());
	}

	@Test(timeout = 10000)
	public void cancelInterruptsWorker() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		OperationExecution exec = execution(() -> {
			entered.countDown();
			Thread.sleep(60_000);
		});
		exec.start(m_executor);
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		assertTrue(exec.cancel());
		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(State.CANCELLED, exec.getState());
		// 인터럽트로 생긴 InterruptedException은 실패로 보고하지 않는다.
		assertEquals(List.of("cancelled"), m_calls);
		assertFalse(exec.cancel());
	}

	@Test(timeout = 10000)
	public void cancelledButCompletedNormallyIsCancelled() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		OperationExecution exec = execution(() -> {
			entered.countDown();
			awaitUninterruptibly(release);
		});
		exec.start(m_executor);
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		assertTrue(exec.cancel());
		// 인터럽트에 반응하지 않는 작업은 끝날 때까지 CANCELLING으로 남는다.
		assertFalse(exec.awaitFinished(Duration.ofMillis(200)));
		assertEquals(State.CANCELLING, exec.getState());
		assertTrue("repeated cancel is accepted", exec.cancel());

		release.countDown();
		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(State.CANCELLED, exec.getState());
		assertEquals(List.of("cancelled"), m_calls);
	}

	@Test(timeout = 10000)
	public void cancelledButFailedIsCancelled() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		OperationExecution exec = execution(() -> {
			entered.countDown();
			awaitUninterruptibly(release);
			throw new IllegalStateException("late failure");
		});
		exec.start(m_executor);
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		assertTrue(exec.cancel());
		release.countDown();
		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(State.CANCELLED, exec.getState());
		assertEquals(List.of("cancelled"), m_calls);
		assertNull(m_failure.get());
	}

	@Test(timeout = 10000)
	public void cancelBeforeStartSkipsWork() throws Exception {
		AtomicBoolean ran = new AtomicBoolean();
		OperationExecution exec = execution(() -> ran.set(true));

		assertTrue(exec.cancel());
		exec.start(m_executor);
		assertTrue(exec.awaitFinished(WAIT));
		assertFalse(ran.get());
		assertEquals(State.CANCELLED, exec.getState());
		assertEquals(List.of("cancelled"), m_calls);
	}

	@Test(timeout = 10000)
	public void cancelInterruptDoesNotLeakToPoolThread() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		OperationExecution exec = execution(() -> {
			entered.countDown();
			awaitUninterruptibly(release);
		});
		exec.start(m_executor);
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		exec.cancel();
		release.countDown();
		assertTrue(exec.awaitFinished(WAIT));

		// 같은 풀 스레드에서 다음 작업을 실행한다.
		Future<Boolean> interrupted = m_executor.submit(() -> Thread.currentThread().isInterrupted());
		assertFalse(interrupted.get(5, TimeUnit.SECONDS));
	}

	@Test(timeout = 10000)
	public void handlerFailureDoesNotBreakFinish() throws Exception {
		OperationExecution exec = new OperationExecution("h1", "op", () -> { },
										() -> { throw new IllegalStateException("handler"); },
										e -> { }, () -> { });
		exec.start(m_executor);
		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(State.COMPLETED, exec.getState());
	}

	@Test(timeout = 10000)
	public void registryFindsAndPurgesFinishedExecutions() throws Exception {
		OperationExecutionRegistry registry = new OperationExecutionRegistry();
		try {
			CountDownLatch release = new CountDownLatch(1);
			OperationExecution running = new OperationExecution("run", "op", () -> release.await(),
																() -> { }, e -> { }, () -> { });
			OperationExecution done = new OperationExecution("done", "op", () -> { },
																() -> { }, e -> { }, () -> { });
			registry.register(running);
			registry.register(done);
			running.start(registry.getExecutor());
			done.start(registry.getExecutor());
			assertTrue(done.awaitFinished(WAIT));

			// 보존 시간 안에는 끝난 실행도 찾을 수 있다.
			assertSame(done, registry.find("done").getOrNull());
			assertTrue(registry.find("unknown").isAbsent());

			registry.setRetainTimeout(Duration.ZERO);
			Thread.sleep(10);
			assertTrue(registry.find("done").isAbsent());
			// 실행 중인 항목은 보존 시간과 관계없이 남는다.
			assertSame(running, registry.find("run").getOrNull());

			registry.cancelAll();
			assertTrue(running.awaitFinished(WAIT));
			assertEquals(State.CANCELLED, running.getState());
		}
		finally {
			registry.shutdown(Duration.ofSeconds(1));
		}
	}

	@Test(timeout = 10000)
	public void registryShutdownCancelsRunningExecutions() throws Exception {
		OperationExecutionRegistry registry = new OperationExecutionRegistry();
		CountDownLatch entered = new CountDownLatch(1);
		OperationExecution exec = new OperationExecution("h", "op", () -> { entered.countDown(); Thread.sleep(60_000); },
															() -> { }, e -> { }, () -> m_calls.add("cancelled"));
		registry.register(exec);
		exec.start(registry.getExecutor());
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		registry.shutdown(WAIT);
		assertEquals(State.CANCELLED, exec.getState());
		assertEquals(List.of("cancelled"), m_calls);
	}

	@Test(timeout = 10000)
	public void cancellationHandlerFailureIsRecordedAndRetried() throws Exception {
		AtomicBoolean saveFails = new AtomicBoolean(true);
		CountDownLatch entered = new CountDownLatch(1);
		OperationExecution exec = new OperationExecution("h1", "op", () -> { entered.countDown(); Thread.sleep(60_000); },
											() -> { }, e -> { },
											() -> {
												if ( saveFails.get() ) {
													throw new IllegalStateException("save failed");
												}
												m_calls.add("cancelled");
											});
		exec.start(m_executor);
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		assertTrue(exec.cancel());
		assertTrue(exec.awaitFinished(WAIT));

		// 처리기가 실패해도 실행은 취소로 끝나고, 실패 원인이 남는다.
		assertEquals(State.CANCELLED, exec.getState());
		assertEquals("save failed", exec.getCancellationHandlerFailure().getMessage());
		assertEquals(OperationExecution.CancellationHandlerState.FAILED, exec.getCancellationHandlerState());
		assertEquals("still failing", OperationExecution.CancellationHandlerState.FAILED, exec.retryCancellationHandler(WAIT));

		saveFails.set(false);
		assertEquals(OperationExecution.CancellationHandlerState.SUCCEEDED, exec.retryCancellationHandler(WAIT));
		assertNull(exec.getCancellationHandlerFailure());
		assertEquals(List.of("cancelled"), m_calls);
		// 성공한 뒤에는 다시 부르지 않는다.
		assertEquals(OperationExecution.CancellationHandlerState.SUCCEEDED, exec.retryCancellationHandler(WAIT));
		assertEquals(List.of("cancelled"), m_calls);
	}

	@Test(timeout = 10000)
	public void slowRetryDoesNotBlockCallersOrStateQueries() throws Exception {
		// 첫 저장은 실패하고, 재시도 저장은 release가 열릴 때까지 걸린다.
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch entered = new CountDownLatch(1);
		OperationExecution exec = new OperationExecution("h1", "op", () -> { entered.countDown(); Thread.sleep(60_000); },
											() -> { }, e -> { },
											() -> {
												if ( attempts.incrementAndGet() == 1 ) {
													throw new IllegalStateException("save failed");
												}
												release.await();
											});
		exec.start(m_executor);
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		assertTrue(exec.cancel());
		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(OperationExecution.CancellationHandlerState.FAILED, exec.getCancellationHandlerState());

		// 재시도는 다른 스레드에서 돌고, 부른 쪽은 주어진 시간만 기다린다.
		long t0 = System.nanoTime();
		assertEquals(OperationExecution.CancellationHandlerState.RUNNING, exec.retryCancellationHandler(Duration.ofMillis(200)));
		assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);
		// 저장 중에도 상태 조회는 바로 답한다.
		assertEquals(OperationExecution.CancellationHandlerState.RUNNING, exec.getCancellationHandlerState());
		// 진행 중인 재시도가 있으면 새로 시작하지 않고 함께 기다린다.
		assertEquals(OperationExecution.CancellationHandlerState.RUNNING, exec.retryCancellationHandler(Duration.ofMillis(100)));
		assertEquals(2, attempts.get());

		release.countDown();
		assertEquals(OperationExecution.CancellationHandlerState.SUCCEEDED, exec.retryCancellationHandler(WAIT));
		assertEquals(2, attempts.get());
		assertNull(exec.getCancellationHandlerFailure());
	}

	/** 첫 저장은 실패하는 취소 처리기. 두 번째부터는 {@code release}가 열릴 때까지 걸리고, {@code failAgain}이면 실패한다. */
	private static OperationExecution cancelledWithFailedSave(OperationExecutionRegistry registry, String handle,
															CountDownLatch release, AtomicBoolean failAgain,
															java.util.concurrent.atomic.AtomicInteger attempts)
		throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		OperationExecution exec = new OperationExecution(handle, "op", () -> { entered.countDown(); Thread.sleep(60_000); },
											() -> { }, e -> { },
											() -> {
												if ( attempts.incrementAndGet() == 1 ) {
													throw new IllegalStateException("save failed");
												}
												release.await();
												if ( failAgain.get() ) {
													throw new IllegalStateException("save failed again");
												}
											});
		registry.register(exec);
		exec.start(registry.getExecutor());
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		assertTrue(exec.cancel());
		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(OperationExecution.CancellationHandlerState.FAILED, exec.getCancellationHandlerState());
		return exec;
	}

	@Test(timeout = 10000)
	public void entryIsKeptWhileSaveRetryRuns() throws Exception {
		OperationExecutionRegistry registry = new OperationExecutionRegistry();
		registry.setRetainTimeout(Duration.ofMillis(200));
		try {
			CountDownLatch release = new CountDownLatch(1);
			java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
			OperationExecution exec = cancelledWithFailedSave(registry, "h-r", release, new AtomicBoolean(false), attempts);

			assertEquals(OperationExecution.CancellationHandlerState.RUNNING, exec.retryCancellationHandler(Duration.ofMillis(50)));
			Thread.sleep(400);		// 처음 끝난 시각부터 보존 시간이 지났다.
			assertSame("kept while the save retry runs", exec, registry.find("h-r").getOrNull());

			release.countDown();
			assertEquals(OperationExecution.CancellationHandlerState.SUCCEEDED, exec.retryCancellationHandler(WAIT));
			// 재시도가 끝난 시각부터 다시 보존 시간 동안 남는다.
			assertSame(exec, registry.find("h-r").getOrNull());
			Thread.sleep(400);
			assertTrue(registry.find("h-r").isAbsent());
			assertTrue(exec.isExpired());
		}
		finally {
			registry.shutdown(Duration.ofSeconds(1));
		}
	}

	@Test(timeout = 10000)
	public void failedSaveIsRetainedFromLastAttempt() throws Exception {
		OperationExecutionRegistry registry = new OperationExecutionRegistry();
		registry.setRetainTimeout(Duration.ofMillis(300));
		try {
			CountDownLatch release = new CountDownLatch(1);
			release.countDown();
			java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
			OperationExecution exec = cancelledWithFailedSave(registry, "h-f", release, new AtomicBoolean(true), attempts);

			Thread.sleep(200);
			assertEquals(OperationExecution.CancellationHandlerState.FAILED, exec.retryCancellationHandler(WAIT));
			Thread.sleep(200);		// 처음 끝난 시각부터는 400ms, 마지막 시도부터는 200ms.
			assertSame("retained from the last attempt", exec, registry.find("h-f").getOrNull());
			Thread.sleep(300);
			assertTrue(registry.find("h-f").isAbsent());
		}
		finally {
			registry.shutdown(Duration.ofSeconds(1));
		}
	}

	@Test(timeout = 10000)
	public void expiredExecutionDoesNotStartRetry() throws Exception {
		OperationExecutionRegistry registry = new OperationExecutionRegistry();
		try {
			CountDownLatch release = new CountDownLatch(1);
			release.countDown();
			java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
			OperationExecution exec = cancelledWithFailedSave(registry, "h-x", release, new AtomicBoolean(false), attempts);

			registry.setRetainTimeout(Duration.ZERO);
			Thread.sleep(10);
			assertTrue(registry.find("h-x").isAbsent());
			assertTrue(exec.isExpired());
			// 지워진 실행에는 재시도가 시작되지 않는다(결과를 확인할 방법이 없으므로).
			assertEquals(OperationExecution.CancellationHandlerState.FAILED, exec.retryCancellationHandler(Duration.ofMillis(100)));
			assertEquals(1, attempts.get());
		}
		finally {
			registry.shutdown(Duration.ofSeconds(1));
		}
	}

	@Test(timeout = 10000)
	public void retryBeforeHandlerRunIsNotSuccess() throws Exception {
		// 처리기를 아직 부르지 않았으면 다시 시도해도 성공으로 보고하지 않는다.
		CountDownLatch entered = new CountDownLatch(1);
		AtomicBoolean handlerCalled = new AtomicBoolean();
		OperationExecution exec = new OperationExecution("h1", "op", () -> { entered.countDown(); Thread.sleep(60_000); },
											() -> { }, e -> { }, () -> handlerCalled.set(true));
		assertEquals(OperationExecution.CancellationHandlerState.NOT_RUN, exec.retryCancellationHandler(WAIT));
		exec.start(m_executor);
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		assertEquals(OperationExecution.CancellationHandlerState.NOT_RUN, exec.retryCancellationHandler(WAIT));
		assertFalse(handlerCalled.get());

		assertTrue(exec.cancel());
		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(OperationExecution.CancellationHandlerState.SUCCEEDED, exec.getCancellationHandlerState());
	}

	@Test(timeout = 10000)
	public void registryRejectsRegistrationAfterShutdown() throws Exception {
		OperationExecutionRegistry registry = new OperationExecutionRegistry();
		registry.shutdown(Duration.ofSeconds(1));
		OperationExecution exec = new OperationExecution("late", "op", () -> { }, () -> { }, e -> { }, () -> { });
		try {
			registry.register(exec);
			org.junit.Assert.fail("registration after shutdown should be rejected");
		}
		catch ( IllegalStateException expected ) { }
		assertTrue(registry.find("late").isAbsent());
	}

	@Test(timeout = 10000)
	public void registeredButNotStartedExecutionIsCancelledByShutdown() throws Exception {
		// 등록은 종료 전에, 시작은 종료 뒤에 일어나도 작업은 수행되지 않는다.
		OperationExecutionRegistry registry = new OperationExecutionRegistry();
		AtomicBoolean ran = new AtomicBoolean();
		OperationExecution exec = new OperationExecution("h", "op", () -> ran.set(true),
															() -> { }, e -> { }, () -> m_calls.add("cancelled"));
		registry.register(exec);
		registry.shutdown(Duration.ofSeconds(1));
		assertEquals(State.CANCELLING, exec.getState());

		exec.start(m_executor);		// 다른 executor라 받아들여진다(닫힌 풀이면 RejectedExecutionException).
		assertTrue(exec.awaitFinished(WAIT));
		assertFalse(ran.get());
		assertEquals(State.CANCELLED, exec.getState());
		assertEquals(List.of("cancelled"), m_calls);
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while ( true ) {
			try {
				latch.await();
				break;
			}
			catch ( InterruptedException e ) {
				interrupted = true;
			}
		}
		if ( interrupted ) {
			Thread.currentThread().interrupt();
		}
	}
}
