package mdt.assetconnection;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.common.provider.MultiFormatSubscriptionProvider;
import de.fraunhofer.iosb.ilt.faaast.service.typing.TypeInfo;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class UnsupportedSubscriptionProvider extends MultiFormatSubscriptionProvider<UnsupportedSubscriptionProviderConfig> {
	protected UnsupportedSubscriptionProvider(UnsupportedSubscriptionProviderConfig config) {
		super(config);
	}

	@SuppressWarnings("rawtypes")
	@Override
	protected TypeInfo getTypeInfo() {
        throw new UnsupportedOperationException("SubscriptionProvider.getTypeInfo");
	}

	@Override
	protected void subscribe() throws AssetConnectionException {
        throw new UnsupportedOperationException("SubscriptionProvider.subscribe");
	}

	@Override
	public void unsubscribe() throws AssetConnectionException {
        throw new UnsupportedOperationException("SubscriptionProvider.unsubscribe");
	}
}
