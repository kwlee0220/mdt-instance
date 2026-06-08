package mdt.ext.rck;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import utils.Preconditions;
import utils.websocket.WebSocketContext;


/**
 * RCK 시뮬레이션 WebSocket 세션의 도메인 컨텍스트.
 * <p>
 * {@link RCKSimulation} 상태 차트와 짝을 이루며, 시뮬레이션 한 건을 식별하는 불변 정보
 * (클라이언트 ID, 공정명, 레이아웃명)와 차트가 진행되면서 채워지는 가변 결과 정보
 * (장비 속성, 시뮬레이션 결과, 결과 비디오, 실패 원인)를 함께 보유한다.
 * <p>
 * 불변 식별 정보는 생성 시점에 확정되며, 가변 결과 정보는 {@link RCKSimulation}의 각 상태
 * 핸들러가 WebSocket 메시지를 처리하는 과정에서 갱신한 뒤, 차트 종료 후 호출자가 조회한다.
 * {@link WebSocketContext}를 CRTP self-bound로 확장하여 RCK 시뮬레이션 전용 컨텍스트를 정의한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationContext extends WebSocketContext<RCKSimulationContext> {
	private final String m_clientId;
	private final String m_processName;
	private final String m_layoutName;

	private JsonNode m_equipmentProperties;
	private RCKSimulationResult m_simulationResult;
	private VideoInfo m_simulationVideo;
	private Throwable m_failureCause;
	
	/**
	 * RCK 시뮬레이션 컨텍스트를 생성한다.
	 *
	 * @param serverUrl		연결할 RCK 시뮬레이션 WebSocket 서버 URL (non-null)
	 * @param clientId		시뮬레이션을 요청한 클라이언트 식별자 (non-null)
	 * @param processName	시뮬레이션 대상 공정명 (non-null)
	 * @param layoutName	시뮬레이션 대상 레이아웃명 (non-null)
	 * @throws IllegalArgumentException 인자 중 하나라도 {@code null}인 경우
	 */
	public RCKSimulationContext(String serverUrl, String clientId, String processName, String layoutName) {
		super(serverUrl);
		
		Preconditions.checkNotNullArgument(clientId, "clientId is null");
		Preconditions.checkNotNullArgument(processName, "processName is null");
		Preconditions.checkNotNullArgument(layoutName, "layoutName is null");
		
		m_clientId = clientId;
		m_processName = processName;
		m_layoutName = layoutName;
		m_simulationResult = RCKSimulationResult.empty();
	}
	
	/**
	 * 시뮬레이션을 요청한 클라이언트 식별자를 반환한다.
	 *
	 * @return	클라이언트 ID (non-null)
	 */
	public String getClientId() {
		return m_clientId;
	}

	/**
	 * 시뮬레이션 대상 공정명을 반환한다.
	 *
	 * @return	공정명 (non-null)
	 */
	public String getProcessName() {
		return m_processName;
	}

	/**
	 * 시뮬레이션 대상 레이아웃명을 반환한다.
	 *
	 * @return	레이아웃명 (non-null)
	 */
	public String getLayoutName() {
		return m_layoutName;
	}

	/**
	 * 시뮬레이션 시작 시 서버로 전송할 장비 속성 정보를 반환한다.
	 *
	 * @return	장비 속성 JSON. 아직 설정되지 않았으면 {@code null}.
	 */
	public JsonNode getEquipmentProperties() {
		return m_equipmentProperties;
	}

	/**
	 * 시뮬레이션 시작 시 서버로 전송할 장비 속성 정보를 설정한다.
	 *
	 * @param equipmentProperties	장비 속성 JSON
	 */
	public void setEquipmentProperties(JsonNode equipmentProperties) {
		m_equipmentProperties = equipmentProperties;
	}

	/**
	 * 현재까지 수신된 시뮬레이션 결과를 반환한다.
	 * <p>
	 * 생성 직후에는 {@link RCKSimulationResult#empty()}로 초기화되어 있으며,
	 * 시뮬레이션이 진행되면서 최신 진행 보고로 갱신된다.
	 *
	 * @return	시뮬레이션 결과 (non-null)
	 */
	public RCKSimulationResult getSimulationResult() {
		return m_simulationResult;
	}

	/**
	 * 시뮬레이션 결과를 설정한다.
	 *
	 * @param simulationResult	시뮬레이션 결과
	 */
	public void setSimulationResult(RCKSimulationResult simulationResult) {
		m_simulationResult = simulationResult;
	}

	/**
	 * 시뮬레이션 종료 후 수신된 결과 비디오 정보를 반환한다.
	 *
	 * @return	결과 비디오 정보. 아직 수신되지 않았으면 {@code null}.
	 */
	public VideoInfo getSimulationVideo() {
		return m_simulationVideo;
	}

	/**
	 * 시뮬레이션 결과 비디오 정보를 설정한다.
	 *
	 * @param simulationVideo	결과 비디오 정보
	 */
	public void setSimulationVideo(VideoInfo simulationVideo) {
		m_simulationVideo = simulationVideo;
	}

	/**
	 * 시뮬레이션 실패 원인을 반환한다.
	 *
	 * @return	실패 원인 예외. 실패하지 않았으면 {@code null}.
	 */
	public Throwable getFailureCause() {
		return m_failureCause;
	}

	/**
	 * 시뮬레이션 실패 원인을 설정한다.
	 *
	 * @param failureCause	실패 원인 예외
	 */
	public void setFailureCause(Throwable failureCause) {
		m_failureCause = failureCause;
	}
	
	/**
	 * 두 컨텍스트가 동일한 시뮬레이션을 가리키는지 비교한다.
	 * <p>
	 * 서버 URL, 공정명, 레이아웃명이 모두 같으면 동치로 본다. 가변 결과 필드
	 * (장비 속성, 시뮬레이션 결과, 비디오, 실패 원인)는 비교에 포함하지 않는다.
	 */
	@Override
	public boolean equals(Object obj) {
		if ( this == obj ) {
			return true;
		}
		if ( obj == null || !(obj instanceof RCKSimulationContext) ) {
			return false;
		}

		RCKSimulationContext other = (RCKSimulationContext) obj;
		return getServerUrl().equals(other.getServerUrl())
				&& m_processName.equals(other.m_processName)
				&& m_layoutName.equals(other.m_layoutName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(getServerUrl(), m_processName, m_layoutName);
	}
	
	@Override
	public String toString() {
		return "RCKSimulationContext[serverUrl=" + getServerUrl() + ",processName=" + m_processName
									+ ",layoutName=" + m_layoutName + "]";
	}
}
