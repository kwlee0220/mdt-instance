package mdt.ext.rck;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import mdt.ext.rck.RCKSimulationResult.Termination;


/**
 * {@link RCKSimulation}을 사용하는 예제 프로그램(자동 테스트 아님).
 * <p>
 * RCK 시뮬레이터({@value #SERVER_URL})에 접속해 레이아웃 하나를 실행하고, 실행 중에는 상태·진척도·생산량·
 * 설비 이용률을 출력하며, 끝나면 종료 사유와 결과 영상 URL을 출력한다.
 * <ul>
 *   <li>최대 대기 시간 안에 끝나지 않으면 {@link RCKSimulation#requestStop()}으로 중지를 요청한다.</li>
 *   <li>Ctrl-C로 종료하면 시뮬레이터에 중지를 요청하고 종료 절차가 끝날 때까지 잠시 기다린다.</li>
 * </ul>
 * 사용법:
 * <pre>
 * RCKSimulationExample [레이아웃 이름] [최대 대기 시간(분)]
 * </pre>
 * 레이아웃 이름은 RCK 서버에 있는 이름이어야 한다. 종료 코드는 정상 완료면 0, 그 밖에는 1이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationSamplePress {
	private static final String SERVER_URL = "ws://59.25.241.80:4000";
	private static final String CLIENT_ID = "mdt";
	private static final String PROCESS_NAME = "press";
	private static final String DEFAULT_LAYOUT = "프레스 공정_사람_NOCON.vcmx";
	private static final long DEFAULT_MAX_WAIT_MINUTES = 50;

	public static final void main(String... args) throws Exception {
		String layoutName = (args.length > 0) ? args[0] : DEFAULT_LAYOUT;
		long maxWaitMinutes = (args.length > 1) ? Long.parseLong(args[1]) : DEFAULT_MAX_WAIT_MINUTES;

		// 실행마다 새 simulation 객체를 만든다.
		RCKSimulation simulation = RCKSimulation.builder()
											.serverUrl(SERVER_URL)
											.clientId(CLIENT_ID)
											.processName(PROCESS_NAME)
											.layoutName(layoutName)
											// 메시지가 이 시간 동안 없으면 실패
											.responseTimeout(Duration.ofMinutes(2))
											// 연결 유지: ping 주기, pong 대기 시간
											.keepAlive(Duration.ofSeconds(30), Duration.ofSeconds(5))
											.observer(new ConsoleObserver())
											.build();

		// Ctrl-C로 끝낼 때 시뮬레이터 쪽 실행도 멈추도록 중지를 요청한다.
		Thread shutdownHook = new Thread(() -> stopQuietly(simulation), "rck-example-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		System.out.printf("RCK 시뮬레이션 시작: server=%s, process=%s, layout=%s%n",
							SERVER_URL, PROCESS_NAME, layoutName);
		simulation.start();
		try {
			simulation.waitForFinished(maxWaitMinutes, TimeUnit.MINUTES);
		}
		catch ( TimeoutException e ) {
			// 대기 시간이 지났다고 실행이 멈추지는 않으므로 직접 중지를 요청한다.
			System.out.printf("%d분 안에 끝나지 않아 중지를 요청합니다.%n", maxWaitMinutes);
			simulation.requestStop();
			simulation.waitForFinished();
		}
		catch ( InterruptedException e ) {
			simulation.cancel(true);
			Thread.currentThread().interrupt();
			throw e;
		}
		finally {
			Runtime.getRuntime().removeShutdownHook(shutdownHook);
		}

		// waitForFinished()의 반환만으로 정상 완료를 판단하지 않고 종료 사유를 확인한다.
		RCKSimulationResult result = simulation.getSimulationResult();
		printResult(result);
		System.exit(result.termination() == Termination.COMPLETED ? 0 : 1);
	}

	private static void stopQuietly(RCKSimulation simulation) {
		if ( simulation.isRunning() ) {
			System.out.println("중지 요청 중...");
			try {
				simulation.requestStop();
				simulation.waitForFinished(15, TimeUnit.SECONDS);
			}
			catch ( Exception ignored ) { }
		}
	}

	private static void printResult(RCKSimulationResult result) {
		RCKSimulationProgress progress = result.progress();
		System.out.println();
		System.out.println("=== 시뮬레이션 결과 ===");
		System.out.println("종료 사유  : " + result.termination());
		if ( result.termination() == Termination.STOPPED ) {
			System.out.println("중지 확인  : " + (result.stopConfirmed() ? "서버가 중지를 확인함" : "서버 확인 없음"));
		}
		System.out.println("진척도     : " + progress.progress());
		System.out.println("생산량     : " + progress.production());
		printUtilizations(progress.utilizations());
		System.out.println("결과 영상  : " + ((result.videoUrl() != null) ? result.videoUrl() : "(없음)"));
		if ( result.failureCause() != null ) {
			System.out.println("실패 원인  : " + result.failureCause());
		}
	}

	private static void printUtilizations(Map<RCKEquipment,Float> utilizations) {
		if ( utilizations.isEmpty() ) {
			System.out.println("설비 이용률: (없음)");
			return;
		}
		System.out.println("설비 이용률:");
		utilizations.forEach((equipment, util) -> System.out.printf("  - %s[%d] %s: %.3f%n",
												equipment.group(), equipment.index(), equipment.name(), util));
	}

	/**
	 * 실행 중 알림을 콘솔에 출력하는 관찰자.
	 * <p>
	 * 콜백은 잠금을 쥔 상태에서 호출될 수 있으므로 출력만 하고 오래 걸리는 작업은 하지 않는다.
	 */
	private static final class ConsoleObserver implements SimulationObserver {
		private int m_lastProgress = -1;

		@Override
		public void onPhaseChanged(RCKSimulationPhase phase) {
			System.out.printf("[상태] %s (%s)%n", phase.displayName(), phase);
		}

		@Override
		public void onProgressUpdated(RCKSimulationProgress progress) {
			// 같은 진척도의 보고가 반복되면 한 번만 출력한다.
			if ( progress.progress() != m_lastProgress ) {
				m_lastProgress = progress.progress();
//				System.out.printf("[진행] 진척도=%d, 생산량=%d, 이용률=%s%n",
//									progress.progress(), progress.production(), progress.utilizations());
			}
		}

		@Override
		public void onVideoReady(String downloadUrl) {
			System.out.println("[영상] " + downloadUrl);
		}

		@Override
		public void onFinished(RCKSimulationResult result) {
			System.out.println("[종료] " + result.termination());
		}
	}
}
