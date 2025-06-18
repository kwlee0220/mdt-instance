package mdt.assetconnection.jdbc;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionConfig;
import mdt.assetconnection.UnsupportedOperationProvider;
import mdt.assetconnection.UnsupportedOperationProviderConfig;
import mdt.assetconnection.UnsupportedSubscriptionProvider;
import mdt.assetconnection.UnsupportedSubscriptionProviderConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JdbcAssetConnectionConfig extends AssetConnectionConfig<JdbcAssetConnection,
																	DefaultJdbcValueProviderConfig,
																	UnsupportedOperationProviderConfig,
																	UnsupportedSubscriptionProviderConfig> {
	private String jdbcConfigKey;
	
	public String getJdbcConfigKey() {
		return this.jdbcConfigKey;
	}
	
	public void setJdbcConfigKey(String key) {
		this.jdbcConfigKey = key;
	}

    public static Builder builder() {
        return new Builder();
    }

    public abstract static class AbstractBuilder<T extends JdbcAssetConnectionConfig,
    												B extends AbstractBuilder<T, B>>
    	extends  AssetConnectionConfig.AbstractBuilder<JdbcAssetConnectionConfig,
            										DefaultJdbcValueProviderConfig, DefaultJdbcValueProvider,
            										UnsupportedOperationProviderConfig, UnsupportedOperationProvider,
            										UnsupportedSubscriptionProviderConfig, UnsupportedSubscriptionProvider,
            										JdbcAssetConnection, B> {
        public B jdbcConfigKey(String value) {
            getBuildingInstance().setJdbcConfigKey(value);
            return getSelf();
        }
    }

    public static class Builder extends AbstractBuilder<JdbcAssetConnectionConfig, Builder> {
        @Override
        protected Builder getSelf() {
            return this;
        }

        @Override
        protected JdbcAssetConnectionConfig newBuildingInstance() {
            return new JdbcAssetConnectionConfig();
        }
    }
}
