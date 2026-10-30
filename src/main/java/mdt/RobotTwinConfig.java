package mdt;

/**
 * RobotTwin 접속 정보.
 * <p>
 * 전역 설정 {@code robotTwins}의 항목이거나, RobotTwin 자산 변수·로봇 연산의 {@code robotTwinConfig}에
 * 인라인으로 적은 객체다({@link ConnectionConfigRef}). RobotTwin REST API의 경로는
 * {@code <robotTwinBaseUrl>/api/v1/robot_twins/<twinId>/...}이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RobotTwinConfig {
	private String m_robotTwinBaseUrl;
	private String m_twinId;

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

	@Override
	public String toString() {
		return String.format("%s: url=%s/api/v1/robot_twins/%s", getClass().getSimpleName(),
							m_robotTwinBaseUrl, m_twinId);
	}
}
