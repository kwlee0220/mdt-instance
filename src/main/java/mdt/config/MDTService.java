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

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTService extends Service {
	private final ServiceConfig m_config;
	private Persistence m_persistence;
	private FileStorage m_fileStorage;
	
	public MDTService(ServiceConfig svc) throws ConfigurationException, AssetConnectionException,
												PersistenceException, MessageBusException {
		super(svc);
		
		m_config = svc;
		
        try {
			m_persistence = (Persistence)ReflectionUtils.getFieldValue(this, "persistence");
			m_fileStorage = (FileStorage)ReflectionUtils.getFieldValue(this, "fileStorage");
		}
		catch ( Throwable e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("failed to access field", cause);
		}
	}
	
	public ServiceConfig getServiceConfig() {
		return m_config;
	}
	
	public Persistence getPersistence() {
		return m_persistence;
	}
	
	public FileStorage getFileStorage() {
		return m_fileStorage;
	}
	
	public MDTInstanceConfig getInstanceConfig() {
		try {
			return ((MDTServiceConfig)ReflectionUtils.getFieldValue(this, "config")).getInstanceConfig();
		}
		catch ( Throwable e ) {
			return null;
		}
	}
}
