package mdt.config;

import java.util.List;

import mdt.config.AASOperationConfig.HttpOperationConfig;
import mdt.config.AASOperationConfig.JavaOperationConfig;
import mdt.config.AASOperationConfig.ProgramOperationConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationsConfig {
	private List<ProgramOperationConfig> m_programOperations = List.of();
	private List<JavaOperationConfig> m_javaOperations = List.of();
	private List<HttpOperationConfig> m_httpOperations = List.of();
	
	public List<ProgramOperationConfig> getProgramOperations() {
		return m_programOperations;
	}
	
	public void setProgramOperations(List<ProgramOperationConfig> programOperations) {
		m_programOperations = programOperations;
	}
	
	public List<JavaOperationConfig> getJavaOperations() {
		return m_javaOperations;
	}
	
	public void setJavaOperations(List<JavaOperationConfig> javaOperations) {
		m_javaOperations = javaOperations;
	}
	
	public List<HttpOperationConfig> getHttpOperations() {
		return m_httpOperations;
	}
	
	public void setHttpOperations(List<HttpOperationConfig> httpOperations) {
		m_httpOperations = httpOperations;
	}
}
