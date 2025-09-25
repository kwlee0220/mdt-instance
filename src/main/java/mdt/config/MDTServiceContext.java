package mdt.config;

import utils.ReflectionUtils;

import de.fraunhofer.iosb.ilt.faaast.service.Service;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.config.ServiceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTServiceContext extends Service {
	public MDTServiceContext(ServiceConfig svc) throws ConfigurationException, AssetConnectionException {
		super(svc);
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
