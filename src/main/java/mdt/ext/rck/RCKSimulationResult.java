package mdt.ext.rck;

import javax.annotation.Nullable;

import utils.Preconditions;


/**
 * 끝난 {@link RCKSimulation}의 결과.
 * <p>
 * 호출자가 상태 차트의 현재 상태 경로와 가변 문맥({@link RCKSimulationContext})을 조합하지 않고도 종료 사유, 마지막
 * 진행 상황, 결과 영상 정보를 한 번에 얻을 수 있게 한다. {@link RCKSimulation#getSimulationResult()}와
 * {@link SimulationObserver#onFinished(RCKSimulationResult)}가 같은 값을 준다.
 *
 * @param termination	종료 사유
 * @param stopConfirmed	{@code Stopped}로 끝났을 때 시뮬레이터가 {@code simulation_stop}으로 중지를 확인했는지 여부.
 * 						응답 대기 시간 초과나 연결 종료·오류로 {@code Stopped}가 되었으면 {@code false}. 다른 종료
 * 						사유에서는 항상 {@code false}.
 * @param progress		마지막으로 받은 진행 상황 (non-null)
 * @param videoInfo		결과 영상 정보. 받지 못했으면 {@code null}.
 * @param failureCause	실패 원인. {@link Termination#FAILED}가 아니면 {@code null}.
 * @author Kang-Woo Lee (ETRI)
 */
public record RCKSimulationResult(Termination termination, boolean stopConfirmed, RCKSimulationProgress progress,
									@Nullable VideoInfo videoInfo, @Nullable Throwable failureCause) {
	/**
	 * 시뮬레이션의 종료 사유.
	 */
	public enum Termination {
		/** 정상 완료 ({@code /Completed}). */
		COMPLETED("Completed", "시뮬레이션 완료"),
		/** {@link RCKSimulation#requestStop()}으로 중지 ({@code /Stopped}). 차트의 실행 결과는 {@code COMPLETED}다. */
		STOPPED("Stopped", "시뮬레이션 중지됨"),
		/** 실패 ({@code /Failed}, 또는 연결 오류 등으로 종료 상태를 거치지 않은 실패). */
		FAILED("Failed", "시뮬레이션 실패"),
		/** 취소 ({@link RCKSimulation#cancel(boolean)}, 시간 초과 등). 종료 상태를 거치지 않는다. */
		CANCELLED("Cancelled", "시뮬레이션 취소됨");

		private final String m_code;
		private final String m_displayName;

		private Termination(String code, String displayName) {
			m_code = code;
			m_displayName = displayName;
		}

		/**
		 * 프로그램이 읽는 결과 코드. 오퍼레이션의 {@code FinalState} 출력에 쓴다.
		 *
		 * @return	결과 코드 (예: {@code "Completed"})
		 */
		public String code() {
			return m_code;
		}

		/**
		 * 사람이 보는 표시 이름. MDT {@code State} 출력에 쓴다. 문구는 바뀔 수 있으므로 분기 조건으로 쓰지 않는다.
		 *
		 * @return	표시 이름 (예: {@code "시뮬레이션 완료"})
		 */
		public String displayName() {
			return m_displayName;
		}
	}

	public RCKSimulationResult {
		Preconditions.checkNotNullArgument(termination, "termination is null");
		Preconditions.checkNotNullArgument(progress, "progress is null");
	}

	/**
	 * 영상 다운로드 URL을 반환한다.
	 *
	 * @return	다운로드 URL. 영상 정보를 받지 못했으면 {@code null}.
	 */
	public @Nullable String videoUrl() {
		return (videoInfo != null) ? videoInfo.downloadUrl() : null;
	}
}
