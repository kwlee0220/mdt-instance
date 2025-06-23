package mdt.config;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.persistence.PersistenceStackConfig;

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
	
	private String m_heartbeatInterval;
	private String m_managerCheckInterval;
	@Nullable private MDTEndpointConfigs m_mdtEndpoints;
	
	private List<PersistenceStackConfig> m_persistenceStacks = Lists.newArrayList();
	private OperationsConfig m_operations;
	
	public String getId() {
		return m_id;
	}
	public void setId(String id) {
		m_id = id;
	}
	
	public String getInstanceEndpoint() {
		return m_instanceEndpoint;
	}
	public void setInstanceEndpoint(String endpoint) {
		m_instanceEndpoint = endpoint;
	}
	
	public String getManagerEndpoint() {
		return m_managerEndpoint;
	}
	public void setManagerEndpoint(String endpoint) {
		m_managerEndpoint = endpoint;
	}
	
	public File getGlobalConfigFile() {
		return m_globalConfigFile;
	}
	public void setGlobalConfigFile(File file) {
		m_globalConfigFile = file;
	}
	
	public File getKeyStoreFile() {
		return m_keyStoreFile;
	}
	public void setKeyStoreFile(File file) {
		m_keyStoreFile = file;
	}

	public String getHeartbeatInterval() {
		return m_heartbeatInterval;
	}
	public void setHeartbeatInterval(String interval) {
		m_heartbeatInterval = interval;
	}
	
	public String getManagerCheckInterval() {
		return m_managerCheckInterval;
	}
	public void setManagerCheckInterval(String interval) {
		m_managerCheckInterval = interval;
	}
}
