package mdt.persistence;

import org.eclipse.digitaltwin.aas4j.v3.model.ExecutionState;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.operation.OperationHandle;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;


/**
 * FA³ST가 비동기 연산 호출({@code invoke-async})마다 만드는 실행 ID({@link OperationHandle})를 가로채는
 * persistence 계층.
 * <p>
 * FA³ST 비동기 호출 처리기는 한 요청 스레드 안에서 실행 ID를 만들고, {@code save(handle, RUNNING)}으로
 * 저장한 뒤, 같은 스레드에서 연산 provider의 {@code invokeAsync}를 호출한다. 이 계층은 {@code RUNNING} 상태의
 * 저장을 볼 때 그 실행 ID를 현재 스레드에 담아 두고, provider는 {@link #takeCapturedHandle()}로 꺼내 실제 실행과
 * 연결한다. 이렇게 연결해야 실행 ID로 연산을 취소할 수 있다.
 * <p>
 * FA³ST의 저장 호출이 반드시 이 계층을 지나도록 {@code MDTInstanceMain}이 설정과 관계없이 항상 가장 바깥에
 * 끼운다. 저장 동작 자체는 바꾸지 않고 모든 요청을 base persistence에 위임한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationHandleCaptureLayer extends PersistenceStack<OperationHandleCaptureLayerConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(OperationHandleCaptureLayer.class);

	private static final ThreadLocal<OperationHandle> s_captured = new ThreadLocal<>();

	private OperationHandleCaptureLayerConfig m_config;

	public OperationHandleCaptureLayer() {
		setLogger(s_logger);
	}

	@Override
	public void init(CoreConfig coreConfig, OperationHandleCaptureLayerConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		super.init(coreConfig, config, serviceContext);

		m_config = config;
	}

	@Override
	public OperationHandleCaptureLayerConfig asConfig() {
		return m_config;
	}

	/**
	 * {@code RUNNING} 상태의 저장이면 실행 ID를 현재 스레드에 담은 뒤 base persistence에 위임한다.
	 */
	@Override
	public void save(OperationHandle handle, OperationResult result) throws PersistenceException {
		if ( handle != null && result != null && result.getExecutionState() == ExecutionState.RUNNING ) {
			s_captured.set(handle);
		}
		super.save(handle, result);
	}

	@Override
	public void deleteAll() throws PersistenceException {
		getBasePersistence().deleteAll();
	}

	/**
	 * 현재 스레드에 담긴 실행 ID를 꺼내고 비운다.
	 * <p>
	 * FA³ST 비동기 호출 처리기가 같은 스레드에서 부른 provider의 {@code invokeAsync}가 맨 앞에서 호출한다.
	 * 앞선 요청이 provider 호출에 이르지 못해 값이 남아 있더라도, 다음 비동기 요청이 같은 스레드에서
	 * {@code RUNNING}을 저장할 때 덮어쓰므로 다른 실행과 잘못 연결되지 않는다.
	 *
	 * @return	담긴 실행 ID. 없으면 {@code null}.
	 */
	public static OperationHandle takeCapturedHandle() {
		OperationHandle handle = s_captured.get();
		s_captured.remove();
		return handle;
	}
}
