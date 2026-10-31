package mdt.ext.rck;

import com.fasterxml.jackson.databind.JsonNode;

import utils.Preconditions;
import utils.websocket.WebSocketContext;

import mdt.ext.rck.VideoMessages.VideoInfo;


/**
 * RCK 시뮬레이션 WebSocket 세션의 도메인 컨텍스트.
 * <p>
 * {@link RCKSimulation} 상태 차트와 짝을 이루며, 시뮬레이션 한 건을 식별하는 불변 정보
 * (클라이언트 ID, 공정명, 레이아웃명)와 차트가 진행되면서 채워지는 가변 결과 정보
 * (설비 정보, 최근 진행 상황, 결과 영상 정보)를 함께 보유한다.
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

	private JsonNode m_facility;
	private RCKSimulationStatus m_latestStatus;
	private VideoInfo m_videoInfo;
	
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
		m_latestStatus = RCKSimulationStatus.empty();
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
	 * 시뮬레이터가 {@code facility} 메시지로 보낸 설비 정보를 반환한다. 시뮬레이션 시작 명령에 그대로 담아 보낸다.
	 *
	 * @return	설비 정보 JSON. 아직 받지 않았으면 {@code null}.
	 */
	public JsonNode getFacility() {
		return m_facility;
	}

	/**
	 * 시뮬레이터가 보낸 설비 정보를 설정한다.
	 *
	 * @param facility	설비 정보 JSON
	 */
	public void setFacility(JsonNode facility) {
		m_facility = facility;
	}

	/**
	 * 시뮬레이터가 마지막으로 보고한 진행 상황을 반환한다.
	 * <p>
	 * 생성 직후에는 {@link RCKSimulationStatus#empty()}이며, {@code simulation_status} 메시지를 받을 때마다 갱신된다.
	 *
	 * @return	최근 진행 상황 (non-null)
	 */
	public RCKSimulationStatus getLatestStatus() {
		return m_latestStatus;
	}

	/**
	 * 최근 진행 상황을 설정한다.
	 *
	 * @param status	진행 상황
	 */
	public void setLatestStatus(RCKSimulationStatus status) {
		m_latestStatus = status;
	}
	
	/**
	 * 결과 영상 정보({@code video_info})를 반환한다.
	 *
	 * @return	결과 영상 정보. 아직 받지 않았으면 {@code null}.
	 */
	public VideoInfo getVideoInfo() {
		return m_videoInfo;
	}
	
	/**
	 * 결과 영상 정보를 설정한다.
	 *
	 * @param videoInfo	결과 영상 정보
	 */
	public void setVideoInfo(VideoInfo videoInfo) {
		m_videoInfo = videoInfo;
	}

	@Override
	public String toString() {
		return "RCKSimulationContext[serverUrl=" + getServerUrl() + ",processName=" + m_processName
									+ ",layoutName=" + m_layoutName + "]";
	}
}
