package mdt.ext.rck;

/**
 * {@link RCKSimulation}의 진행을 외부(MDT 인스턴스 등)에 알리는 통로.
 * <p>
 * {@link #onFinished}를 뺀 메소드는 상태 차트의 신호 처리 스레드에서 차트 lock을 쥔 채 호출된다. {@link #onFinished}는
 * 차트의 종료 리스너에서 호출되며, 시작 전에 취소된 경우에는 차트 lock 없이 실행 결과 lock만 쥔 채 호출된다. 어느
 * 쪽이든 lock을 쥐고 있으므로 오래 걸리는 작업(원격 호출 등)은 다른 스레드로 넘기고 바로 반환해야 한다. 구현이 던진
 * 예외는 경고 로그만 남기고 무시되므로 시뮬레이션 진행에 영향을 주지 않는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface SimulationObserver {
	/** 아무 일도 하지 않는 관찰자. */
	public static final SimulationObserver NONE = new SimulationObserver() { };

	/**
	 * 시뮬레이션이 새 상태에 진입했다. 종료 상태는 {@link #onFinished(String, String, Throwable)}로 알린다.
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

	/**
	 * 시뮬레이션이 끝났다. 종료 상태({@code Completed}/{@code Stopped}/{@code Failed})에 들어간 경우뿐 아니라, 연결
	 * 오류로 인한 대체 종료나 취소처럼 종료 상태를 거치지 않고 끝난 경우에도 정확히 한 번 호출된다.
	 *
	 * @param resultCode	결과 코드. {@code Completed}, {@code Stopped}, {@code Failed}, {@code Cancelled} 중 하나.
	 * 						{@code Completed}/{@code Stopped}는 오퍼레이션의 {@code FinalState} 출력과 같은 값이다.
	 * @param displayName	사용자에게 보일 최종 상태 이름 (예: {@code "시뮬레이션 실패"})
	 * @param cause			실패 원인. 실패가 아니면 {@code null}.
	 */
	public default void onFinished(String resultCode, String displayName, Throwable cause) { }
}
