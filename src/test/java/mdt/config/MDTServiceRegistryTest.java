package mdt.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;

import org.junit.Test;

import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;

import mdt.assetconnection.operation.OperationExecutionRegistry;
import mdt.endpoint.opcontrol.OperationControlEndpoint;
import mdt.endpoint.opcontrol.OperationControlEndpointConfig;


/**
 * FA³ST가 {@code super()} 안에서 endpoint와 연산 provider를 초기화할 때도 {@link MDTService}의 실행 목록을 얻을 수
 * 있는지 확인한다.
 * <p>
 * 생성자를 실행하지 않은 객체로 "필드 초기화식이 아직 실행되지 않은" 상태를 재현한다.
 */
public class MDTServiceRegistryTest {
	private static MDTService serviceBeforeFieldInit() {
		return mock(MDTService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
	}

	@Test
	public void registryIsAvailableBeforeFieldInitialization() {
		MDTService svc = serviceBeforeFieldInit();
		OperationExecutionRegistry registry = svc.getOperationExecutionRegistry();
		assertNotNull(registry);
		assertSame(registry, svc.getOperationExecutionRegistry());
		registry.shutdown(Duration.ofSeconds(1));
	}

	@Test
	public void operationControlEndpointInitializesWithinServiceConstruction() {
		MDTService svc = serviceBeforeFieldInit();
		OperationControlEndpoint endpoint = new OperationControlEndpoint();
		endpoint.init(new CoreConfig(), new OperationControlEndpointConfig(0, Duration.ofSeconds(2),
																		Duration.ofSeconds(7), null), svc);
		assertEquals(Duration.ofSeconds(7), svc.getOperationExecutionRegistry().getRetainTimeout());
		svc.getOperationExecutionRegistry().shutdown(Duration.ofSeconds(1));
	}
}
