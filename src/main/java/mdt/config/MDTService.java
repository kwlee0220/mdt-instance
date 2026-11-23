package mdt.config;

import de.fraunhofer.iosb.ilt.faaast.service.Service;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.config.ServiceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.MessageBusException;
import de.fraunhofer.iosb.ilt.faaast.service.filestorage.FileStorage;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;

import utils.InternalException;
import utils.ReflectionUtils;
import utils.Throwables;

import mdt.assetconnection.operation.OperationExecutionRegistry;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTService extends Service {
	private final ServiceConfig m_config;
	private Persistence<?> m_persistence;
	private FileStorage<?> m_fileStorage;
	// FA³ST는 super() 안에서 연산 provider와 endpoint를 만들고 초기화하므로, 그 시점에 이 필드의 초기화식은 아직
	// 실행되지 않았다. 그래서 초기화식을 두지 않고 getOperationExecutionRegistry()가 처음 불릴 때 만든다. 초기화식이
	// 없으면 super()가 끝난 뒤 다시 대입되지 않으므로 super() 안에서 만든 값이 그대로 남는다.
	private OperationExecutionRegistry m_opExecutions;

	public MDTService(ServiceConfig svc) throws ConfigurationException, AssetConnectionException,
												PersistenceException, MessageBusException {
		super(svc);

		m_config = svc;

        try {
			m_persistence = (Persistence<?>)ReflectionUtils.getFieldValue(this, "persistence");
			m_fileStorage = (FileStorage<?>)ReflectionUtils.getFieldValue(this, "fileStorage");
		}
		catch ( Throwable e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("failed to access field", cause);
		}
	}

	public ServiceConfig getServiceConfig() {
		return m_config;
	}

	public Persistence<?> getPersistence() {
		return m_persistence;
	}

	public FileStorage<?> getFileStorage() {
		return m_fileStorage;
	}

	/**
	 * 비동기로 호출된 AAS 연산의 실행 목록. 실행 ID로 실행을 찾아 취소할 때 쓴다.
	 */
	public synchronized OperationExecutionRegistry getOperationExecutionRegistry() {
		if ( m_opExecutions == null ) {
			m_opExecutions = new OperationExecutionRegistry();
		}
		return m_opExecutions;
	}

	public MDTInstanceConfig getInstanceConfig() {
		try {
			return ((MDTServiceConfig)ReflectionUtils.getFieldValue(this, "config")).getInstanceConfig();
		}
		catch ( Throwable e ) {
			return null;
		}
	}

	/**
	 * 실행 중인 AAS 연산을 취소한 뒤 서비스를 멈춘다. 취소된 연산이 결과를 persistence에 저장할 수 있도록
	 * 서비스보다 먼저 멈춘다.
	 */
	@Override
	public void stop() {
		getOperationExecutionRegistry().shutdown(OperationExecutionRegistry.DEFAULT_SHUTDOWN_WAIT);
		super.stop();
	}
}
