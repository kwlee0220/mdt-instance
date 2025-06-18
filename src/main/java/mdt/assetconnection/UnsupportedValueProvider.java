package mdt.assetconnection;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.common.provider.MultiFormatValueProvider;
import de.fraunhofer.iosb.ilt.faaast.service.typing.TypeInfo;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class UnsupportedValueProvider extends MultiFormatValueProvider<UnsupportedValueProviderConfig> {
	protected UnsupportedValueProvider(UnsupportedValueProviderConfig config) {
		super(config);
	}

	@Override
	protected TypeInfo getTypeInfo() {
        throw new UnsupportedOperationException("ValueProvider.getTypeInfo");
	}

	@Override
	public byte[] getRawValue() throws AssetConnectionException {
        throw new UnsupportedOperationException("ValueProvider.getRawValue");
	}

	@Override
	public void setRawValue(byte[] value) throws AssetConnectionException {
        throw new UnsupportedOperationException("ValueProvider.setRawValue");
	}
}
