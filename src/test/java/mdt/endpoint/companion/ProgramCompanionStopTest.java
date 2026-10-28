package mdt.endpoint.companion;

import static org.junit.Assert.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import com.google.common.util.concurrent.Service.State;

import utils.async.command.ProgramServiceConfig;


/**
 * {@link ProgramCompanion#stop()}이 프로그램 종료를 기다린 뒤에 JVM 종료 hook을 해제하는지 검증한다.
 * 실제 자식 프로세스({@code sh}, {@code sleep})를 실행한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanionStopTest {
	private ProgramCompanion m_companion;

	@After
	public void tearDown() {
		// 테스트가 실패해도 자식 프로세스와 hook이 남지 않도록 충분히 기다려 정리한다.
		if ( m_companion != null && m_companion.isShutdownHookRegistered() ) {
			m_companion.setTerminationTimeout(Duration.ofSeconds(10));
			m_companion.stop();
		}
	}

	@Test
	public void testStopWaitsForTermination() throws Exception {
		m_companion = startCompanion(List.of("sleep", "30"));

		m_companion.stop();

		// stop()이 반환되었을 때 이미 종료되어 있어야 하며, hook도 해제되어야 한다.
		assertEquals(State.TERMINATED, m_companion.getProgramState());
		assertFalse(m_companion.isShutdownHookRegistered());
	}

	@Test
	public void testStopKeepsHookOnTimeout() throws Exception {
		// SIGTERM을 무시하므로 1초 grace 후 SIGKILL로 종료된다. 대기 시간(200ms)보다 오래 걸린다.
		m_companion = startCompanion(List.of("sh", "-c", "trap '' TERM; sleep 30"));
		m_companion.setTerminationTimeout(Duration.ofMillis(200));

		m_companion.stop();

		// 아직 종료되지 않았으므로 JVM 종료 시 정리하도록 hook이 남아 있어야 한다.
		assertNotEquals(State.TERMINATED, m_companion.getProgramState());
		assertTrue(m_companion.isShutdownHookRegistered());

		// 충분히 기다리는 stop()을 다시 부르면 종료되고 hook이 해제된다.
		m_companion.setTerminationTimeout(Duration.ofSeconds(10));
		m_companion.stop();
		assertEquals(State.TERMINATED, m_companion.getProgramState());
		assertFalse(m_companion.isShutdownHookRegistered());
	}

	@Test
	public void testStopBeforeStart() {
		m_companion = initCompanion(List.of("sleep", "30"));

		m_companion.stop();

		assertEquals(State.TERMINATED, m_companion.getProgramState());
		assertFalse(m_companion.isShutdownHookRegistered());
	}

	private static ProgramCompanion initCompanion(List<String> commandLine) {
		ProgramServiceConfig programConfig = new ProgramServiceConfig();
		programConfig.setCommandLine(commandLine);

		ProgramCompanionConfig config = new ProgramCompanionConfig();
		config.setProgramConfig(programConfig);

		ProgramCompanion companion = new ProgramCompanion();
		companion.init(null, config, null);
		return companion;
	}

	private static ProgramCompanion startCompanion(List<String> commandLine) throws Exception {
		ProgramCompanion companion = initCompanion(commandLine);
		companion.start();

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while ( companion.getProgramState() != State.RUNNING ) {
			assertTrue("program did not reach RUNNING", System.nanoTime() < deadline);
			Thread.sleep(20);
		}
		return companion;
	}
}
