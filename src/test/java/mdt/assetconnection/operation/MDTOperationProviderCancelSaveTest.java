package mdt.assetconnection.operation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.ExecutionState;
import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationResult;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultKey;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperationResult;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.operation.OperationHandle;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;

import mdt.config.MDTService;
import mdt.persistence.OperationHandleCaptureLayer;


/**
 * 취소된 실행의 {@code CANCELED} 저장 재시도와 실패 기록을 실제 {@link MDTOperationProvider} 경로로 확인한다.
 */
public class MDTOperationProviderCancelSaveTest {
	private static final Duration WAIT = Duration.ofSeconds(5);

	/** 인터럽트될 때까지 기다리는 연산. */
	public static class BlockingProvider implements OperationProvider {
		public BlockingProvider(ServiceContext context, Reference ref, JavaOperationProviderConfig config) { }

		@Override
		public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
								OperationVariable[] outputVars) throws Exception {
			Thread.sleep(60_000);
		}
	}

	private OperationExecutionRegistry m_registry;
	private Persistence<?> m_persistence;
	private MDTOperationProvider m_provider;

	@Before
	public void setUp() throws Exception {
		Reference ref = new DefaultReference.Builder().type(ReferenceTypes.MODEL_REFERENCE)
				.keys(List.of(new DefaultKey.Builder().type(KeyTypes.SUBMODEL).value("urn:sm").build(),
						new DefaultKey.Builder().type(KeyTypes.OPERATION).value("Op").build())).build();
		m_registry = new OperationExecutionRegistry();
		m_persistence = mock(Persistence.class);
		MDTService svc = mock(MDTService.class);
		when(svc.getOperationExecutionRegistry()).thenReturn(m_registry);
		doReturn(m_persistence).when(svc).getPersistence();
		when(svc.getOperationOutputVariables(ref)).thenReturn(new OperationVariable[0]);

		MDTOperationProviderConfig config = new MDTOperationProviderConfig();
		config.setJava(new JavaOperationProviderConfig(BlockingProvider.class.getName(), Map.of()));
		m_provider = new MDTOperationProvider(svc, ref, config);
	}

	@After
	public void tearDown() {
		m_registry.shutdown(Duration.ofSeconds(1));
	}

	private OperationExecution startAndCancel(String handleId) throws Exception {
		// FA³ST 비동기 처리기처럼 같은 스레드에서 RUNNING 저장 → invokeAsync 순서로 부른다.
		OperationHandleCaptureLayer layer = new OperationHandleCaptureLayer();
		layer.setBasePersistence(mock(Persistence.class));
		OperationHandle handle = OperationHandle.builder().handleId(handleId).build();
		layer.save(handle, new DefaultOperationResult.Builder().executionState(ExecutionState.RUNNING).build());
		m_provider.invokeAsync(new OperationVariable[0], new OperationVariable[0], (o, io) -> { }, e -> { });

		OperationExecution exec = m_registry.find(handleId).getOrNull();
		assertNotNull(exec);
		Thread.sleep(100);
		assertTrue(exec.cancel());
		assertTrue(exec.awaitFinished(WAIT));
		return exec;
	}

	@Test(timeout = 15_000)
	public void transientSaveFailureIsRetried() throws Exception {
		doThrow(new PersistenceException("busy")).doThrow(new PersistenceException("busy")).doNothing()
			.when(m_persistence).save(any(OperationHandle.class), any(OperationResult.class));

		OperationExecution exec = startAndCancel("h-retry");
		assertEquals(OperationExecution.State.CANCELLED, exec.getState());
		assertNull(exec.getCancellationHandlerFailure());
		verify(m_persistence, times(3)).save(any(OperationHandle.class), any(OperationResult.class));
	}

	@Test(timeout = 15_000)
	public void persistentSaveFailureIsRecorded() throws Exception {
		doThrow(new PersistenceException("down"))
			.when(m_persistence).save(any(OperationHandle.class), any(OperationResult.class));

		OperationExecution exec = startAndCancel("h-fail");
		assertEquals(OperationExecution.State.CANCELLED, exec.getState());
		assertTrue(exec.getCancellationHandlerFailure() instanceof PersistenceException);
		verify(m_persistence, times(MDTOperationProvider.CANCELLED_SAVE_ATTEMPTS))
			.save(any(OperationHandle.class), any(OperationResult.class));

		// 저장이 되살아나면 다시 시도해 성공한다.
		doNothing().when(m_persistence).save(any(OperationHandle.class), any(OperationResult.class));
		assertEquals(OperationExecution.CancellationHandlerState.SUCCEEDED, exec.retryCancellationHandler(WAIT));
		assertNull(exec.getCancellationHandlerFailure());
	}
}
