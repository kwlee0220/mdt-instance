package mdt.assetconnection.jdbc;


import java.io.IOException;

import org.eclipse.digitaltwin.aas4j.v3.model.Reference;

import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AbstractAssetConnection;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import mdt.MDTGlobalConfigurations;
import mdt.assetconnection.UnsupportedOperationProvider;
import mdt.assetconnection.UnsupportedOperationProviderConfig;
import mdt.assetconnection.UnsupportedSubscriptionProvider;
import mdt.assetconnection.UnsupportedSubscriptionProviderConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JdbcAssetConnection extends AbstractAssetConnection<JdbcAssetConnection,
																JdbcAssetConnectionConfig,
																DefaultJdbcValueProviderConfig,
																DefaultJdbcValueProvider,
																UnsupportedOperationProviderConfig,
																UnsupportedOperationProvider,
																UnsupportedSubscriptionProviderConfig,
																UnsupportedSubscriptionProvider> {
	private JdbcProcessor m_jdbc;
	
	@Override
	public String getEndpointInformation() {
		return config.getJdbcConfigKey();
	}

	@Override
	protected void doConnect() throws AssetConnectionException {
		try {
			JdbcConfiguration jdbcConf = MDTGlobalConfigurations.getJdbcConfig(config.getJdbcConfigKey());
			m_jdbc = JdbcProcessor.create(jdbcConf);
		}
		catch ( Exception e ) {
			String msg = String.format("Failed to connect, cause=%s", e);
			throw new AssetConnectionException(msg, e);
		}
	}

	@Override
	protected void doDisconnect() throws AssetConnectionException {
		m_jdbc = null;
	}

	@Override
	protected DefaultJdbcValueProvider createValueProvider(Reference reference,
													DefaultJdbcValueProviderConfig providerConfig)
		throws AssetConnectionException {
		try {
			return new DefaultJdbcValueProvider(this.serviceContext, reference, providerConfig, m_jdbc);
		}
		catch ( IOException e ) {
			String msg = String.format("Failed to connect DefaultJdbcValueProvider, cause=%s", e);
			throw new AssetConnectionException(msg, e);
		}
	}

	@Override
	protected UnsupportedOperationProvider createOperationProvider(Reference reference,
																	UnsupportedOperationProviderConfig providerConfig)
		throws AssetConnectionException {
        throw new UnsupportedOperationException("getting operation via JDBC currently not supported.");
	}

	@Override
	protected UnsupportedSubscriptionProvider createSubscriptionProvider(Reference reference,
																UnsupportedSubscriptionProviderConfig providerConfig)
		throws AssetConnectionException {
        throw new UnsupportedOperationException("executing subscription via JDBC currently not supported.");
	}
}
