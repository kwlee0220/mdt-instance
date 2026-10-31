package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import mdt.model.sm.ref.MDTArgumentReference;
import mdt.model.sm.value.PropertyValue;


/**
 * {@link MDTSimulationOutputs}가 출력 인자 준비와 갱신을 별도 스레드에서 하고, 밀린 갱신은 인자별로 최신 값만
 * 쓰는지 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTSimulationOutputsTest {
	private static void awaitLast(List<Object> written, Object expected) throws InterruptedException {
		long due = System.currentTimeMillis() + 5000;
		while ( (written.isEmpty() || !expected.equals(written.get(written.size() - 1)))
				&& System.currentTimeMillis() < due ) {
			Thread.sleep(20);
		}
		assertEquals(expected, written.isEmpty() ? null : written.get(written.size() - 1));
	}

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
		MDTSimulationOutputs outputs = new MDTSimulationOutputs(() -> Map.of(MDTSimulationOutputs.ARG_STATE, stateRef));

		long started = System.nanoTime();
		outputs.onStateChanged("/Registering", "등록중");
		outputs.onStateChanged("/LayoutLoading", "레이아웃 로딩중");
		outputs.onStateChanged("/Running", "진행중");
		outputs.onFinished("Completed", "완료", null);
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		assertTrue("observer calls must not wait for MDT: " + elapsedMillis + "ms", elapsedMillis < 500);

		release.countDown();
		// 갱신 스레드가 첫 값을 집기 전에 모두 들어왔으면 한 번에, 첫 갱신이 막힌 뒤에 들어왔으면 나머지가 합쳐져
		// 두 번 쓰인다. 어느 쪽이든 마지막에 쓰인 값은 최신 값이다.
		awaitLast(written, "완료");
		Thread.sleep(200);
		verify(stateRef, atMost(2)).updatePropertyValue(any());
	}

	@Test(timeout = 10_000)
	public void arguments_are_prepared_lazily_on_the_writer_thread_and_once() throws Exception {
		// 생성자는 MDT에 접속하지 않는다. 출력 인자 준비는 첫 갱신 때 갱신 스레드에서 한 번만 한다.
		CountDownLatch slowMdt = new CountDownLatch(1);
		AtomicInteger loads = new AtomicInteger();
		List<Object> written = new CopyOnWriteArrayList<>();
		MDTArgumentReference stateRef = recordingRef(written, new CountDownLatch(0));
		MDTSimulationOutputs outputs = new MDTSimulationOutputs(() -> {
			loads.incrementAndGet();
			try {
				slowMdt.await();
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
			return Map.of(MDTSimulationOutputs.ARG_STATE, stateRef);
		});
		assertEquals(0, loads.get());

		long started = System.nanoTime();
		outputs.reset();
		outputs.onStateChanged("/Running", "진행중");
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		assertTrue("preparing outputs must not block the caller: " + elapsedMillis + "ms", elapsedMillis < 500);

		slowMdt.countDown();
		awaitLast(written, "진행중");
		outputs.onFinished("Completed", "완료", null);
		awaitLast(written, "완료");
		assertEquals(1, loads.get());
	}

	@Test(timeout = 10_000)
	public void missing_arguments_are_skipped_and_failures_do_not_stop_other_writes() throws Exception {
		MDTArgumentReference progressRef = mock(MDTArgumentReference.class);
		MDTArgumentReference productionRef = mock(MDTArgumentReference.class);
		doAnswer(inv -> { throw new java.io.IOException("down"); }).when(progressRef).updatePropertyValue(any());
		MDTSimulationOutputs outputs = new MDTSimulationOutputs(() -> Map.of(
										MDTSimulationOutputs.ARG_PROGRESS, progressRef,
										MDTSimulationOutputs.ARG_PRODUCTION, productionRef));

		outputs.onStatus(RCKSimulationStatus.empty());

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
		MDTSimulationOutputs outputs = new MDTSimulationOutputs(() -> Map.of(MDTSimulationOutputs.ARG_STATE, stateRef));

		outputs.onStateChanged("/Registering", "등록중");
		verify(stateRef, timeout(5000)).updatePropertyValue(any());
		outputs.onFinished("Completed", "완료", null);

		awaitLast(written, "완료");
	}
}
