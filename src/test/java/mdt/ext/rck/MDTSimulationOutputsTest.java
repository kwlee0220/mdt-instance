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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import mdt.model.sm.ref.MDTArgumentReference;
import mdt.model.sm.value.PropertyValue;


/**
 * {@link MDTSimulationOutputs}가 갱신을 별도 스레드에서 하고, 밀린 갱신은 인자별로 최신 값만 쓰는지 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTSimulationOutputsTest {
	@Test(timeout = 10_000)
	public void writes_do_not_block_the_caller_and_keep_only_the_latest_value() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		List<Object> written = new CopyOnWriteArrayList<>();
		MDTArgumentReference stateRef = mock(MDTArgumentReference.class);
		doAnswer(inv -> {
			release.await();		// MDT가 느린 상황
			written.add(((PropertyValue<?>)inv.getArgument(0)).toValueObject());
			return null;
		}).when(stateRef).updatePropertyValue(any());
		MDTSimulationOutputs outputs = new MDTSimulationOutputs(stateRef, null, null, null, null);

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
		long due = System.currentTimeMillis() + 5000;
		while ( (written.isEmpty() || !"완료".equals(written.get(written.size() - 1)))
				&& System.currentTimeMillis() < due ) {
			Thread.sleep(20);
		}
		assertEquals("완료", written.get(written.size() - 1));
		Thread.sleep(200);
		verify(stateRef, atMost(2)).updatePropertyValue(any());
	}

	@Test(timeout = 10_000)
	public void missing_arguments_are_skipped_and_failures_do_not_stop_other_writes() throws Exception {
		MDTArgumentReference progressRef = mock(MDTArgumentReference.class);
		MDTArgumentReference productionRef = mock(MDTArgumentReference.class);
		doAnswer(inv -> { throw new java.io.IOException("down"); }).when(progressRef).updatePropertyValue(any());
		MDTSimulationOutputs outputs = new MDTSimulationOutputs(null, progressRef, productionRef, null, null);

		outputs.onStatus(RCKSimulationStatus.empty());

		verify(productionRef, timeout(5000)).updatePropertyValue(any());
	}
}
