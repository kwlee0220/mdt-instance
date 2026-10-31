package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import mdt.ext.rck.RCKSimulationResult.Termination;
import mdt.model.sm.ref.MDTArgumentReference;
import mdt.model.sm.value.PropertyValue;


/**
 * {@link MDTSimulationOutputReporter}가 출력 인자 준비와 갱신을 별도 스레드에서 하고, 실행이 바뀌어도 갱신 순서를
 * 지키며, 밀린 갱신은 인자별로 최신 값만 쓰는지 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTSimulationOutputReporterTest {
	private static final String STATE = MDTSimulationOutputReporter.ARG_STATE;

	private static RCKSimulationResult result(Termination termination) {
		return new RCKSimulationResult(termination, false, RCKSimulationProgress.empty(), null, null);
	}

	private static void awaitLast(List<Object> written, Object expected) throws InterruptedException {
		long due = System.currentTimeMillis() + 5000;
		while ( (written.isEmpty() || !expected.equals(written.get(written.size() - 1)))
				&& System.currentTimeMillis() < due ) {
			Thread.sleep(20);
		}
		assertEquals(expected, written.isEmpty() ? null : written.get(written.size() - 1));
	}

	/** 쓰기마다 {@code release}를 기다린 뒤 값을 기록하는 reference. */
	private static MDTArgumentReference recordingRef(List<Object> written, CountDownLatch release) throws Exception {
		MDTArgumentReference ref = mock(MDTArgumentReference.class);
		doAnswer(inv -> {
			release.await();
			written.add(((PropertyValue<?>)inv.getArgument(0)).toValueObject());
			return null;
		}).when(ref).updatePropertyValue(any());
		return ref;
	}

	@Test(timeout = 10_000)
	public void writes_do_not_block_the_caller_and_keep_only_the_latest_value() throws Exception {
		CountDownLatch release = new CountDownLatch(1);		// MDT가 느린 상황
		List<Object> written = new CopyOnWriteArrayList<>();
		MDTArgumentReference stateRef = recordingRef(written, release);
		MDTSimulationOutputReporter reporter = new MDTSimulationOutputReporter(names -> Map.of(STATE, stateRef));

		long started = System.nanoTime();
		SimulationObserver run = reporter.startRun();
		run.onStateChanged("/Registering", "등록중");
		run.onStateChanged("/LayoutLoading", "레이아웃 로딩중");
		run.onStateChanged("/Running", "진행중");
		run.onFinished(result(Termination.COMPLETED));
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		assertTrue("observer calls must not wait for MDT: " + elapsedMillis + "ms", elapsedMillis < 500);

		release.countDown();
		// 갱신 스레드가 첫 값을 집기 전에 모두 들어왔으면 한 번에, 첫 갱신이 막힌 뒤에 들어왔으면 나머지가 합쳐져
		// 두 번 쓰인다. 어느 쪽이든 마지막에 쓰인 값은 최신 값이다.
		awaitLast(written, "시뮬레이션 완료");
		Thread.sleep(200);
		verify(stateRef, atMost(2)).updatePropertyValue(any());
	}

	@Test(timeout = 10_000)
	public void a_late_write_of_the_previous_run_does_not_overwrite_the_next_run() throws Exception {
		// 이전 실행의 "완료" 쓰기가 MDT에서 지연되는 동안 다음 실행이 시작되어도, 최종 값은 다음 실행의 상태다.
		// (실행마다 갱신기를 따로 두던 때는 늦게 끝난 이전 쓰기가 다음 실행의 상태를 덮어썼다.)
		CountDownLatch firstWriteStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstWrite = new CountDownLatch(1);
		List<Object> written = new CopyOnWriteArrayList<>();
		AtomicInteger calls = new AtomicInteger();
		MDTArgumentReference stateRef = mock(MDTArgumentReference.class);
		doAnswer(inv -> {
			Object value = ((PropertyValue<?>)inv.getArgument(0)).toValueObject();
			if ( calls.incrementAndGet() == 1 ) {
				firstWriteStarted.countDown();
				releaseFirstWrite.await();
			}
			written.add(value);
			return null;
		}).when(stateRef).updatePropertyValue(any());
		MDTSimulationOutputReporter reporter = new MDTSimulationOutputReporter(names -> Map.of(STATE, stateRef));

		SimulationObserver first = reporter.startRun();
		first.onFinished(result(Termination.COMPLETED));
		firstWriteStarted.await();				// 이전 실행의 쓰기가 전송 중

		SimulationObserver second = reporter.startRun();
		second.onStateChanged("/ConnectingWebSocket", "소켓 연결중");
		first.onStateChanged("/Running", "진행중");	// 이전 실행의 늦은 갱신은 버려진다
		releaseFirstWrite.countDown();

		awaitLast(written, "소켓 연결중");
		reporter.flushAsync().get(5, TimeUnit.SECONDS);
		assertEquals(List.of("시뮬레이션 완료", "소켓 연결중"), written);
	}

	@Test(timeout = 10_000)
	public void flush_async_completes_after_pending_writes() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		List<Object> written = new CopyOnWriteArrayList<>();
		MDTArgumentReference stateRef = recordingRef(written, release);
		MDTSimulationOutputReporter reporter = new MDTSimulationOutputReporter(names -> Map.of(STATE, stateRef));
		assertTrue("nothing pending", reporter.flushAsync().isDone());

		reporter.startRun().onFinished(result(Termination.FAILED));
		CompletableFuture<Void> flushed = reporter.flushAsync();
		Thread.sleep(100);
		assertFalse("a write is still pending", flushed.isDone());

		release.countDown();
		flushed.get(5, TimeUnit.SECONDS);
		assertEquals("시뮬레이션 실패", written.get(written.size() - 1));
	}

	@Test(timeout = 10_000)
	public void arguments_are_prepared_lazily_on_the_writer_thread_and_retried_per_run() throws Exception {
		// 생성자와 startRun()은 MDT에 접속하지 않는다. 준비하지 못한 인자는 다음 실행에서 다시 준비한다.
		CountDownLatch slowMdt = new CountDownLatch(1);
		List<List<String>> resolveCalls = new CopyOnWriteArrayList<>();
		List<Object> written = new CopyOnWriteArrayList<>();
		MDTArgumentReference stateRef = recordingRef(written, new CountDownLatch(0));
		MDTSimulationOutputReporter reporter = new MDTSimulationOutputReporter(names -> {
			resolveCalls.add(names);
			try {
				slowMdt.await();
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
			return resolveCalls.size() == 1 ? Map.of() : Map.of(STATE, stateRef);	// 처음엔 실패
		});

		long started = System.nanoTime();
		SimulationObserver first = reporter.startRun();
		first.onStateChanged("/Running", "진행중");
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		assertTrue("preparing outputs must not block the caller: " + elapsedMillis + "ms", elapsedMillis < 500);

		slowMdt.countDown();
		reporter.flushAsync().get(5, TimeUnit.SECONDS);
		assertTrue("nothing is written while State is not prepared", written.isEmpty());

		reporter.startRun().onStateChanged("/Running", "진행중");
		reporter.flushAsync().get(5, TimeUnit.SECONDS);
		assertEquals(2, resolveCalls.size());
		assertEquals("진행중", written.get(written.size() - 1));
	}

	@Test(timeout = 10_000)
	public void missing_arguments_are_skipped_and_failures_do_not_stop_other_writes() throws Exception {
		MDTArgumentReference progressRef = mock(MDTArgumentReference.class);
		MDTArgumentReference productionRef = mock(MDTArgumentReference.class);
		doAnswer(inv -> { throw new java.io.IOException("down"); }).when(progressRef).updatePropertyValue(any());
		MDTSimulationOutputReporter reporter = new MDTSimulationOutputReporter(names -> Map.of(
										MDTSimulationOutputReporter.ARG_PROGRESS, progressRef,
										MDTSimulationOutputReporter.ARG_PRODUCTION, productionRef));

		reporter.startRun();

		verify(productionRef, timeout(5000)).updatePropertyValue(any());
	}

	@Test(timeout = 10_000)
	public void an_error_in_a_write_does_not_stop_later_writes() throws Exception {
		// 갱신 중 Error가 나도 갱신 작업이 끝난 것으로 표시되어, 이후의 갱신이 쌓이기만 하지 않는다.
		List<Object> written = new CopyOnWriteArrayList<>();
		AtomicInteger calls = new AtomicInteger();
		MDTArgumentReference stateRef = mock(MDTArgumentReference.class);
		doAnswer(inv -> {
			if ( calls.incrementAndGet() == 1 ) {
				throw new AssertionError("unexpected");
			}
			written.add(((PropertyValue<?>)inv.getArgument(0)).toValueObject());
			return null;
		}).when(stateRef).updatePropertyValue(any());
		MDTSimulationOutputReporter reporter = new MDTSimulationOutputReporter(names -> Map.of(STATE, stateRef));

		SimulationObserver run = reporter.startRun();
		run.onStateChanged("/Registering", "등록중");
		verify(stateRef, timeout(5000)).updatePropertyValue(any());
		run.onFinished(result(Termination.COMPLETED));

		awaitLast(written, "시뮬레이션 완료");
	}
}
