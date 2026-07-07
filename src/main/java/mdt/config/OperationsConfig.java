package mdt.config;

import java.util.Collections;
import java.util.Map;

import mdt.config.AASOperationConfig.HttpConfig;
import mdt.config.AASOperationConfig.JavaConfig;
import mdt.config.AASOperationConfig.ProgramConfig;
import mdt.config.AASOperationConfig.ScriptConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationsConfig {
	private Map<String,ProgramConfig> m_programConfigMap = Collections.emptyMap();
	private Map<String,JavaConfig> m_javaConfigMap = Collections.emptyMap();
	private Map<String,ScriptConfig> m_scriptConfigMap = Collections.emptyMap();
	private Map<String,HttpConfig> m_httpConfigMap = Collections.emptyMap();
	
	public Map<String,ProgramConfig> getPrograms() {
		return m_programConfigMap;
	}
	
	public void setPrograms(Map<String,ProgramConfig> programConfigMap) {
		m_programConfigMap = programConfigMap;
	}
	
	public Map<String,JavaConfig> getJavas() {
		return m_javaConfigMap;
	}
	
	public void setJavas(Map<String,JavaConfig> javaConfigMap) {
		m_javaConfigMap = javaConfigMap;
	}
	
	public Map<String,ScriptConfig> getScripts() {
		return m_scriptConfigMap;
	}
	
	public void setScripts(Map<String,ScriptConfig> scriptConfigMap) {
		m_scriptConfigMap = scriptConfigMap;
	}
	
	public Map<String,HttpConfig> getHttps() {
		return m_httpConfigMap;
	}
	
	public void setHttps(Map<String,HttpConfig> httpConfigMap) {
		m_httpConfigMap = httpConfigMap;
	}
}
