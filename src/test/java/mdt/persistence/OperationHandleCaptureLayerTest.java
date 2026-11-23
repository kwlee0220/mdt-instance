package mdt.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.digitaltwin.aas4j.v3.model.ExecutionState;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperation;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperationResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.ArgumentValidationMode;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionManager;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.messagebus.MessageBus;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.operation.OperationHandle;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.request.submodel.InvokeOperationAsyncRequest;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.response.submodel.InvokeOperationAsyncResponse;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;
import de.fraunhofer.iosb.ilt.faaast.service.request.handler.RequestExecutionContext;
import de.fraunhofer.iosb.ilt.faaast.service.request.handler.submodel.InvokeOperationAsyncRequestHandler;


/**
 * {@link OperationHandleCaptureLayer} 테스트.
 * <p>
 * 실행 ID 가로채기는 FA³ST 비동기 호출 처리기의 호출 순서(같은 스레드에서 {@code save(handle, RUNNING)} →
 * provider {@code invokeAsync})에 기댄다. {@link #faaastAsyncHandlerHandleIsCapturedBeforeProviderCall()}는 실제
 * FA³ST 처리기를 실행해 이 순서를 고정한다. FA³ST를 올린 뒤 이 테스트가 깨지면 가로채기 방식을 다시 검토해야 한다.
 */
public class OperationHandleCaptureLayerTest {
	private Persistence<?> m_base;
	private OperationHandleCaptureLayer m_layer;

	@Before
	public void setUp() throws Exception {
		m_base = mock(Persistence.class);
		doReturn(new DefaultOperation.Builder().idShort("Op").build())
			.when(m_base).getSubmodelElement(any(SubmodelElementIdentifier.class), any(QueryModifier.class));
		m_layer = new OperationHandleCaptureLayer();
		m_layer.setBasePersistence(m_base);
		OperationHandleCaptureLayer.takeCapturedHandle();
	}

	@After
	public void tearDown() {
		OperationHandleCaptureLayer.takeCapturedHandle();
	}

	@Test
	public void faaastAsyncHandlerHandleIsCapturedBeforeProviderCall() throws Exception {
		AssetConnectionManager acm = mock(AssetConnectionManager.class);
		when(acm.hasOperationProvider(any(Reference.class))).thenReturn(true);
		when(acm.getOperationInputValidationMode(any(Reference.class)))
			.thenReturn(Optional.of(ArgumentValidationMode.NONE));
		when(acm.getOperationInoutputValidationMode(any(Reference.class)))
			.thenReturn(Optional.of(ArgumentValidationMode.NONE));

		AtomicReference<OperationHandle> captured = new AtomicReference<>();
		AtomicReference<Thread> providerThread = new AtomicReference<>();
		doAnswer(inv -> {
			captured.set(OperationHandleCaptureLayer.takeCapturedHandle());
			providerThread.set(Thread.currentThread());
			return null;
		}).when(acm).invokeAsync(any(), any(), any(), any(), any());

		RequestExecutionContext context = mock(RequestExecutionContext.class);
		doReturn(m_layer).when(context).getPersistence();
		when(context.getAssetConnectionManager()).thenReturn(acm);
		when(context.getMessageBus()).thenReturn(mock(MessageBus.class));
		when(context.getCoreConfig()).thenReturn(new CoreConfig());

		InvokeOperationAsyncRequest request = InvokeOperationAsyncRequest.builder()
																		.submodelId("urn:test:sm")
																		.path("Op")
																		.build();
		InvokeOperationAsyncResponse response = new InvokeOperationAsyncRequestHandler().doProcess(request, context);

		assertNotNull(response.getPayload());
		assertEquals(response.getPayload(), captured.get());
		assertSame(Thread.currentThread(), providerThread.get());
		// 꺼낸 값은 비워진다.
		assertNull(OperationHandleCaptureLayer.takeCapturedHandle());
		verify(m_base).save(any(OperationHandle.class), any());
	}

	@Test
	public void onlyRunningSaveIsCaptured() throws Exception {
		OperationHandle handle = new OperationHandle();
		m_layer.save(handle, new DefaultOperationResult.Builder().executionState(ExecutionState.COMPLETED).build());
		assertNull(OperationHandleCaptureLayer.takeCapturedHandle());

		m_layer.save(handle, new DefaultOperationResult.Builder().executionState(ExecutionState.RUNNING).build());
		assertEquals(handle, OperationHandleCaptureLayer.takeCapturedHandle());
		verify(m_base, org.mockito.Mockito.times(2)).save(any(OperationHandle.class), any());
	}

	@Test
	public void captureIsPerThread() throws Exception {
		m_layer.save(new OperationHandle(),
					new DefaultOperationResult.Builder().executionState(ExecutionState.RUNNING).build());

		AtomicReference<OperationHandle> other = new AtomicReference<>(new OperationHandle());
		Thread th = new Thread(() -> other.set(OperationHandleCaptureLayer.takeCapturedHandle()));
		th.start();
		th.join();
		assertNull(other.get());
		assertNotNull(OperationHandleCaptureLayer.takeCapturedHandle());
	}

	@Test
	public void laterRunningSaveOverwritesLeftover() throws Exception {
		OperationHandle first = new OperationHandle();
		OperationHandle second = new OperationHandle();
		m_layer.save(first, new DefaultOperationResult.Builder().executionState(ExecutionState.RUNNING).build());
		m_layer.save(second, new DefaultOperationResult.Builder().executionState(ExecutionState.RUNNING).build());
		assertEquals(second, OperationHandleCaptureLayer.takeCapturedHandle());
	}
}
