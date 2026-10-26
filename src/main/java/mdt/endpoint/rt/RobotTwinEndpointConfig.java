package mdt.endpoint.rt;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import lombok.experimental.Delegate;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "robotTwinBaseUrl", "twinId", "pollInterval", "reconnectInterval" })
public class RobotTwinEndpointConfig extends EndpointConfig<RobotTwinEndpoint> {
	@Delegate  private RobotTwinConfig m_robotTwinConfig;
	
	public RobotTwinEndpointConfig(RobotTwinConfig conf) {
		m_robotTwinConfig = conf;
	}
	
	/**
	 * 로봇 트윈 REST API의 기본 URL을 반환한다.
	 * <p>
	 * 로봇 트윈 REST API의 URL 경로에 사용된다 (예: {@code /api/v1/robot_twins/panda01/variables}).
	 * 
	 * @return 로봇 트윈 REST API의 기본 URL.
	 */
	public String getRobotTwinBaseUrl() {
		return m_robotTwinConfig.getRobotTwinBaseUrl();
	}

	/**
	 * 상태를 조회할 로봇 트윈의 식별자를 반환한다.
	 * <p>
	 * 로봇 트윈 REST API의 URL 경로에 사용된다 (예: {@code /api/v1/robot_twins/panda01/variables}).
	 *
	 * @return 로봇 트윈 식별자.
	 */
	public String getTwinId() {
		return m_robotTwinConfig.getTwinId();
	}

	/**
	 * 로봇 트윈의 전체 상태를 조회하는 주기를 반환한다.
	 *
	 * @return 상태 조회 주기.
	 */
	public Duration getPollInterval() {
		return m_robotTwinConfig.getPollInterval();
	}

	public Duration getReconnectInterval() {
		return m_robotTwinConfig.getReconnectInterval();
	}
	
	@Override
	public String toString() {
		return String.format("%s[robotTwinBaseUrl=%s, twinId=%s, pollInterval=%s, reconnectInterval=%s]",
							getClass().getSimpleName(), getRobotTwinBaseUrl(), getTwinId(), getPollInterval(),
							getReconnectInterval());
	}

	public static class RobotTwinConfig {
		private String m_robotTwinBaseUrl;
		private String m_twinId;
		private Duration m_pollInterval = Duration.ofSeconds(1);
		private Duration m_reconnectInterval = Duration.ofSeconds(10);
		
		public String getRobotTwinBaseUrl() {
			return m_robotTwinBaseUrl;
		}
		public void setRobotTwinBaseUrl(String robotTwinBaseUrl) {
			m_robotTwinBaseUrl = robotTwinBaseUrl;
		}
		
		public String getTwinId() {
			return m_twinId;
		}
		public void setTwinId(String twinId) {
			m_twinId = twinId;
		}
		
		public Duration getPollInterval() {
			return m_pollInterval;
		}
		public void setPollInterval(Duration pollInterval) {
			m_pollInterval = pollInterval;
		}
		
		public Duration getReconnectInterval() {
			return m_reconnectInterval;
		}
		public void setReconnectInterval(Duration reconnectInterval) {
			m_reconnectInterval = reconnectInterval;
		}
		
		@Override
		public String toString() {
			return String.format("%s[robotTwinBaseUrl=%s, twinId=%s, pollInterval=%s, reconnectInterval=%s]",
								getClass().getSimpleName(), getRobotTwinBaseUrl(), getTwinId(), getPollInterval(),
								getReconnectInterval());
		}
	}
}
