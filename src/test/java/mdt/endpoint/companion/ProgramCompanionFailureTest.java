package mdt.endpoint.companion;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Service.State;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import utils.async.command.ProgramServiceConfig;
import utils.async.command.RestartPolicy;


/**
 * 동반 프로그램이 FAILED가 되면 companion logger로 ERROR 로그가 남는지,
 * {@code ProgramService}의 로그가 companion logger 아래로 나오는지 검증한다.
 * 실제 자식 프로세스({@code sh}, {@code sleep})를 실행한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanionFailureTest {
	private static final String PROGRAM_LOGGER = ProgramCompanion.class.getName() + ".companion";

	private Logger m_logger;
	private ListAppender<ILoggingEvent> m_appender;
	private ProgramCompanion m_companion;

	@Before
	public void setUp() {
		m_logger = (Logger)LoggerFactory.getLogger(ProgramCompanion.class);
		m_appender = new ListAppender<>();
		m_appender.start();
		m_logger.addAppender(m_appender);
	}

	@After
	public void tearDown() {
		if ( m_companion != null ) {
			m_companion.stop();
		}
		m_logger.detachAppender(m_appender);
	}

	@Test
	public void testStartFailureIsLogged() throws Exception {
		// 실행 파일이 없으므로 RUNNING에 이르지 못하고 FAILED가 된다.
		m_companion = startCompanion(List.of("/nonexistent/companion-program"), RestartPolicy.ALWAYS);

		ILoggingEvent error = awaitEvent(this::isFailureError);
		String msg = error.getFormattedMessage();
		assertTrue(msg, msg.contains("name=companion"));
		assertTrue(msg, msg.contains("state=STARTING"));
		assertTrue(msg, msg.contains("/nonexistent/companion-program"));
		assertEquals(State.FAILED, m_companion.getProgramState());

		// ProgramService 자신의 로그도 companion logger 아래 이름으로 나온다.
		synchronized ( m_appender ) {
			assertTrue(m_appender.list.stream().anyMatch(ev -> ev.getLoggerName().equals(PROGRAM_LOGGER)));
		}
	}

	@Test
	public void testFailureWhileRunningIsLogged() throws Exception {
		// 시작(RUNNING)한 뒤 실패로 끝나고, 재시작하지 않는 정책이므로 FAILED가 된다.
		m_companion = startCompanion(List.of("sh", "-c", "sleep 0.2; exit 3"), RestartPolicy.NO);

		ILoggingEvent error = awaitEvent(this::isFailureError);
		assertTrue(error.getFormattedMessage(), error.getFormattedMessage().contains("state=RUNNING"));
	}

	@Test
	public void testNormalStopIsNotLogged() throws Exception {
		m_companion = startCompanion(List.of("sleep", "30"), RestartPolicy.ALWAYS);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while ( m_companion.getProgramState() != State.RUNNING ) {
			assertTrue("program did not reach RUNNING", System.nanoTime() < deadline);
			Thread.sleep(20);
		}

		m_companion.stop();
		m_companion = null;

		assertFalse(m_appender.list.stream().anyMatch(this::isFailureError));
	}

	private boolean isFailureError(ILoggingEvent ev) {
		return ev.getLevel() == Level.ERROR && ev.getLoggerName().equals(ProgramCompanion.class.getName())
			&& ev.getFormattedMessage().startsWith("companion program failed");
	}

	private ILoggingEvent awaitEvent(Predicate<ILoggingEvent> pred) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while ( true ) {
			// AppenderBase.doAppend()가 appender 객체로 동기화하므로 같은 객체로 잠그고 읽는다.
			synchronized ( m_appender ) {
				for ( ILoggingEvent ev: List.copyOf(m_appender.list) ) {
					if ( pred.test(ev) ) {
						return ev;
					}
				}
			}
			assertTrue("expected log event did not arrive", System.nanoTime() < deadline);
			Thread.sleep(20);
		}
	}

	private static ProgramCompanion startCompanion(List<String> commandLine, RestartPolicy policy) throws Exception {
		ProgramServiceConfig programConfig = new ProgramServiceConfig();
		programConfig.setCommandLine(commandLine);
		programConfig.setRestartPolicy(policy);

		ProgramCompanionConfig config = new ProgramCompanionConfig();
		config.setProgramConfig(programConfig);

		ProgramCompanion companion = new ProgramCompanion();
		companion.init(null, config, null);
		companion.start();
		return companion;
	}
}
