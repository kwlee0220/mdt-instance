package mdt.persistence.asset;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import mdt.persistence.MDTPersistenceStackConfig;
import mdt.persistence.PersistenceStackConfig;

import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AssertVariableBasedPersistenceConfig extends PersistenceStackConfig<AssertVariableBasedPersistence> {
	private final Core m_config;
	
	public AssertVariableBasedPersistenceConfig(Core config, PersistenceConfig<?> baseConfig) {
		super(baseConfig);
		Preconditions.checkArgument(config != null, "AssertVariableBasedPersistenceConfig.Core is null");
		
		m_config = config;
	}
	
	@JsonProperty("assetVariables")
	public List<AssetVariableConfig> getAssetVariableConfigs() {
		return m_config.getAssetVariableConfigs();
	}

	@JsonProperty("assetVariables")
	public void setAssetVariableConfigs(List<AssetVariableConfig> configs) {
		Preconditions.checkArgument(configs != null, "AssetVariableConfig list is null");
		
		m_config.setAssetVariableConfigs(configs);
	}

	public static class Core implements MDTPersistenceStackConfig {
		private List<AssetVariableConfig> m_assetVariableConfigs = List.of();

		@JsonProperty("assetVariables")
		public List<AssetVariableConfig> getAssetVariableConfigs() {
			return m_assetVariableConfigs;
		}

		@JsonProperty("assetVariables")
		public void setAssetVariableConfigs(List<AssetVariableConfig> assetVariableConfigs) {
			m_assetVariableConfigs = assetVariableConfigs;
		}
	}
}
