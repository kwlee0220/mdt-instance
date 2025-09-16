package mdt.config;

import de.fraunhofer.iosb.ilt.faaast.service.Service;
import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.config.ServiceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTServiceContext extends Service implements ServiceContext {
	private final MDTInstanceConfig m_instConfig;
	
	public MDTServiceContext(ServiceConfig config, MDTInstanceConfig instConfig)
		throws ConfigurationException, AssetConnectionException {
		super(config);
		
		m_instConfig = instConfig;
	}

	public MDTInstanceConfig getInstanceConfig() {
		return m_instConfig;
	}
}
