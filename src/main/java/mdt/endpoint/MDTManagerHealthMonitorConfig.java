package mdt.endpoint;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;

import utils.UnitUtils;

/**
 * {@link MDTManagerHealthMonitor}의 설정 정보를 표현하는 클래스.
 * <p>
 * JSON 형식:
 * <pre><code>
 * {
 *    "mdtUrl": &lt;확인 대상 MDTManager의 접속 URL> (예: "http://localhost:8080/mdt"),
 *    "checkInterval": &lt;접속 확인 주기> (예: "10s", "1m", "100ms"),
 *    "enabled": &lt;접속 확인 활성화 여부> (예: true, false)
 * }</code></pre>
 * 
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
public class MDTManagerHealthMonitorConfig extends EndpointConfig<MDTManagerHealthMonitor> {
	private final String m_mdtUrl;
	private final Duration m_checkInterval;
	private final boolean m_enabled; 
	
	@JsonCreator
	public MDTManagerHealthMonitorConfig(@JsonProperty("mdtUrl") String mdtUrl,
										@JsonProperty("checkInterval") String checkInterval,
										@JsonProperty("enabled") Boolean enabled) {
		Preconditions.checkArgument(mdtUrl != null, "'mdtUrl' is missing");
		Preconditions.checkArgument(checkInterval != null, "'checkInterval' is missing");
		
		m_mdtUrl = mdtUrl;
		m_checkInterval = UnitUtils.parseDuration(checkInterval);
		m_enabled = (enabled != null) ? enabled : true;
	}
	
	/**
	 * Health monitoring을 확인할 대상 MDT Manager의 endpoint를 반환한다.
	 * 
	 * @return	Health monitoring 대상 MDT Manager의 endpoint.
	 */
	@JsonProperty("mdtUrl")
	public String getMdtUrl() {
		return m_mdtUrl;
	}
	
	/**
	 * Health monitoring을 수행할 주기를 반환한다.
	 * 
	 * @return Health monitoring 주기.
	 */
	public Duration getCheckInterval() {
		return m_checkInterval;
	}
	
	/**
	 * Health monitoring을 수행할 주기를 반환한다.
	 * 
	 * @return Health monitoring 주기.
	 */
	@JsonProperty("checkInterval")
	public String getCheckIntervalAsString() {
		return m_checkInterval.toString();
	}
	
	/**
	 * Health monitoring 수행 여부를 반환한다.
	 * 
	 * @return Health monitoring 수행 여부.
	 */
	@JsonProperty("enabled")
	public boolean isEnabled() {
		return m_enabled;
	}
}
