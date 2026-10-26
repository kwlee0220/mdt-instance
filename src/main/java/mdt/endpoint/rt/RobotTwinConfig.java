package mdt.endpoint.rt;

import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RobotTwinConfig {
	private String m_robotTwinBaseUrl;
	private String m_twinId;
	private Map<String,String> m_operations = new HashMap<>();
	
	public String getRobotTwinBaseUrl() {
		return m_robotTwinBaseUrl;
	}
	
	public void setRobotTwinBaseUrl(String baseUrl) {
		m_robotTwinBaseUrl = baseUrl;
	}
	
	public String getTwinId() {
		return m_twinId;
	}
	
	public void setTwinId(String twinId) {
		m_twinId = twinId;
	}
	
	public Map<String,String> getOperations() {
		return m_operations;
	}
	
	public void setOperations(Map<String,String> operations) {
		m_operations = operations;
	}
	
	@Override
	public String toString() {
		return String.format("%s: url=%s/api/v1/robot_twins/%s", getClass().getSimpleName(),
							m_robotTwinBaseUrl, m_twinId);
	}
}