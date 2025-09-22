package mdt.config;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.model.AASUtils;
import mdt.persistence.MDTPersistenceStackConfig;
import mdt.persistence.asset.AssetVariableConfig;
import mdt.persistence.timeseries.TimeSeriesSubmodelConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class MDTInstanceConfig {
	private String m_id;
	private String m_instanceEndpoint;
	private String m_managerEndpoint;
	
	private File m_globalConfigFile;
	
	private File m_keyStoreFile;
	private String m_keyStorePassword;
	private String m_keyPassword;
	
	private String m_heartbeatInterval;
	private String m_managerCheckInterval;
	
	private @Nullable ServiceEndpointConfigs m_serviceEndpoints;
	private List<AssetVariableConfig> m_assetVariables = Lists.newArrayList();
	private List<MDTPersistenceStackConfig> m_persistenceStacks = Lists.newArrayList();
	private List<TimeSeriesSubmodelConfig> m_timeSeriesSubmodels = Lists.newArrayList();
	private OperationsConfig m_operations;
	
	public String getSubmodelEndpoint(String submodelId) {
		String smIdEncoded = AASUtils.encodeBase64UrlSafe(submodelId);
		return String.format("%s/submodels/%s", m_instanceEndpoint, smIdEncoded);
	}
	
	public void setKeyStorePassword(String password) {
		m_keyStorePassword = password;
		if ( m_keyPassword == null ) {
			m_keyPassword = password;
		}
	}
	public void setKeyPassword(String password) {
		m_keyPassword = password;
		if ( m_keyStorePassword == null ) {
			m_keyStorePassword = password;
		}
	}
}
