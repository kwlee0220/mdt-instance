package mdt.ext.rck;

/**
 * {@link RCKSimulation}의 진행을 외부(MDT 인스턴스 등)에 알리는 통로.
 * <p>
 * 모든 메소드는 상태 차트의 신호 처리 스레드에서 차트 lock을 쥔 채 호출된다. 구현이 던진 예외는 경고 로그만
 * 남기고 무시되므로 시뮬레이션 진행에 영향을 주지 않는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface SimulationObserver {
	/** 아무 일도 하지 않는 관찰자. */
	public static final SimulationObserver NONE = new SimulationObserver() { };

	/**
	 * 시뮬레이션이 새 상태에 진입했다.
	 *
	 * @param statePath		진입한 상태의 경로 (예: {@code "/Running"})
	 * @param displayName	사용자에게 보일 상태 이름 (예: {@code "시뮬레이션 진행중"})
	 */
	public default void onStateChanged(String statePath, String displayName) { }

	/**
	 * 시뮬레이터가 진행 상황을 보고했다.
	 *
	 * @param status	보고된 진행 상황
	 */
	public default void onStatus(RCKSimulationStatus status) { }

	/**
	 * 결과 영상의 다운로드 URL이 정해졌다.
	 *
	 * @param downloadUrl	결과 영상 다운로드 URL
	 */
	public default void onVideoReady(String downloadUrl) { }
}
