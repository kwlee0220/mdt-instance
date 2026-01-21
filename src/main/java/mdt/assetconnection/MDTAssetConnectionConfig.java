package mdt.assetconnection;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionConfig;

import mdt.assetconnection.operation.MDTOperationProviderConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTAssetConnectionConfig extends AssetConnectionConfig<MDTAssetConnection,
																	UnsupportedValueProviderConfig,
																	MDTOperationProviderConfig,
																	UnsupportedSubscriptionProviderConfig> {


    @Override
	public boolean equalsIgnoringProviders(Object obj) {
    	return false;
    }
}
