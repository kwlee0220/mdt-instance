package mdt.ext.rck;


/**
 * 진행 중인 {@link RCKSimulation}의 단계. {@link SimulationObserver#onPhaseChanged(RCKSimulationPhase)}로 알린다.
 * <p>
 * 종료 상태는 단계가 아니라 {@link RCKSimulationResult.Termination}으로 알린다
 * ({@link SimulationObserver#onFinished(RCKSimulationResult)}).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public enum RCKSimulationPhase {
	/** 시뮬레이터에 WebSocket으로 접속하는 중. */
	CONNECTING("소켓 연결중"),
	/** 클라이언트를 등록하고 {@code connected} 응답을 기다리는 중. */
	REGISTERING("시뮬레이터 등록중"),
	/** 레이아웃을 요청하고 설비 정보({@code facility})를 기다리는 중. */
	LOADING_LAYOUT("레이아웃 로딩중"),
	/** 시작 명령을 보내고 {@code simulation_start}를 기다리는 중. */
	STARTING("시뮬레이션 시작중"),
	/** 시뮬레이션이 진행되며 진행 보고를 받는 중. */
	RUNNING("시뮬레이션 진행중"),
	/** 중지 명령을 보내고 {@code simulation_stop} 응답을 기다리는 중. */
	STOPPING("시뮬레이션 중지중");

	private final String m_displayName;

	private RCKSimulationPhase(String displayName) {
		m_displayName = displayName;
	}

	/**
	 * 사람이 보는 표시 이름. MDT {@code State} 출력에 쓴다. 문구는 바뀔 수 있으므로 분기 조건으로 쓰지 않는다.
	 *
	 * @return	표시 이름 (예: {@code "시뮬레이션 진행중"})
	 */
	public String displayName() {
		return m_displayName;
	}
}
