package mdt.ext.rck;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;



/**
 * 실제 RCK 시뮬레이터에 접속해 시뮬레이션을 단독 실행하는 개발용 프로그램(자동 테스트 아님).
 * <p>
 * 시뮬레이터 서버 주소·공정명·레이아웃을 지정하여 {@link RCKSimulation}을 생성·구동하고 종료를 기다린다.
 * 50분 안에 끝나지 않으면 {@link RCKSimulation#requestStop()}으로 멈춘다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationManualRun {
	public static final void main(String... args) throws Exception {
        String serverUrl = "wss://press.rck.dscloud.biz";
//        String serverUrl = "ws://localhost:4000/rck/simulator";
        String processName = "press";
//		String layoutName = "프레스 공정_사람+설비추가_NOCON.vcmx";
//		String layoutName = "프레스 공정_AMR_NOCON.vcmx";
//		String layoutName = "프레스 공정_설비추가_NOCON.vcmx";
		String layoutName = "프레스 공정_사람_NOCON.vcmx";
        
		RCKSimulationContext ctxt = new RCKSimulationContext(serverUrl, "mdt", processName, layoutName);
        RCKSimulation sim = new RCKSimulation(ctxt);
        sim.setPingInterval(Duration.ofSeconds(30));
        sim.setPongTimeout(Duration.ofSeconds(5));
        sim.start();
        
        try {
        	sim.waitForFinished(50, TimeUnit.MINUTES);
			
        	VideoInfo video = ctxt.getVideoInfo();
	        System.out.println("Simulation video url: " + (video != null ? video.downloadUrl() : "(none)"));
		}
		catch ( TimeoutException e ) {
	        if ( sim.isRunning() ) {
	        	sim.requestStop();
	        	sim.waitForFinished();
			}
		}
	}
}
