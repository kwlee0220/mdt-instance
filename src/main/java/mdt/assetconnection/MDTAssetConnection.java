package mdt.assetconnection;


import java.io.IOException;

import org.eclipse.digitaltwin.aas4j.v3.model.Reference;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AbstractAssetConnection;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import mdt.assetconnection.operation.MDTOperationProvider;
import mdt.assetconnection.operation.MDTOperationProviderConfig;
import mdt.model.MDTModelSerDe;
import mdt.model.MDTSubstitutor;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTAssetConnection extends AbstractAssetConnection<MDTAssetConnection,
																	MDTAssetConnectionConfig,
																	UnsupportedValueProviderConfig,
																	UnsupportedValueProvider,
																	MDTOperationProviderConfig,
																	MDTOperationProvider,
																	UnsupportedSubscriptionProviderConfig,
																	UnsupportedSubscriptionProvider> {
	@Override
	public String getEndpointInformation() {
		return null;
	}

	@Override
	protected void doConnect() throws AssetConnectionException { }

	@Override
	protected void doDisconnect() throws AssetConnectionException { }

	@Override
	protected UnsupportedValueProvider createValueProvider(Reference reference,
															UnsupportedValueProviderConfig providerConfig)
		throws AssetConnectionException {
        throw new UnsupportedOperationException("getting value via MQTT currently not supported.");
	}

	@Override
	protected MDTOperationProvider createOperationProvider(Reference reference,
															MDTOperationProviderConfig providerConfig)
		throws AssetConnectionException {
		try {
			String jsonStr = MDTModelSerDe.toJsonString(providerConfig);
			String substituted = MDTSubstitutor.substibute(jsonStr);
			providerConfig = MDTModelSerDe.readValue(substituted, MDTOperationProviderConfig.class);
			return new MDTOperationProvider(this.serviceContext, reference, providerConfig);
		}
		catch ( IOException e ) {
			throw new AssetConnectionException(e);
		}
	}

	@Override
	protected UnsupportedSubscriptionProvider createSubscriptionProvider(Reference reference,
																UnsupportedSubscriptionProviderConfig providerConfig)
		throws AssetConnectionException {
        throw new UnsupportedOperationException("executing subscription via MQTT currently not supported.");
	}
}
