package mdt.endpoint.companion;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;


/**
 * companion 설정의 복사 경계를 검증한다.
 * <ul>
 *   <li>{@link ProgramCompanionConfig#from}은 원본 설정을 바꾸지 않는다.</li>
 *   <li>{@link ProgramCompanion}은 {@code init()} 시점의 설정으로 동작하고, 이후 설정 변경의 영향을 받지 않는다.</li>
 * </ul>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanionSnapshotTest {
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
	public void testFromDoesNotModifySource() {
		CompanionConfig source = newCompanionConfig(List.of("/bin/true"));
		source.setEnvironments(Map.of("MODE", "fast"));

		ProgramCompanionConfig a = ProgramCompanionConfig.from("a", source, Map.of("MDT_INSTANCE_ID", "A"));
		ProgramCompanionConfig b = ProgramCompanionConfig.from("b", source, Map.of("MDT_INSTANCE_ID", "B"));

		// 각 설정에 해당 인스턴스의 값이 들어가고, 원본은 그대로이다.
		assertEquals(Map.of("MDT_INSTANCE_ID", "A", "MODE", "fast"), a.getProgramConfig().getEnvironments());
		assertEquals(Map.of("MDT_INSTANCE_ID", "B", "MODE", "fast"), b.getProgramConfig().getEnvironments());
		assertEquals(Map.of("MODE", "fast"), source.getEnvironments());

		// 반환된 설정끼리, 그리고 원본과 실행 설정을 공유하지 않는다.
		assertNotSame(a.getProgramConfig(), b.getProgramConfig());
		assertNotSame(source, a.getProgramConfig());
		assertNotSame(a.getProgramConfig().getEnvironments(), b.getProgramConfig().getEnvironments());
	}

	@Test
	public void testFromKeepsRequired() {
		CompanionConfig source = newCompanionConfig(List.of("/bin/true"));
		source.setRequired(true);

		ProgramCompanionConfig config = ProgramCompanionConfig.from("a", source, Map.of());
		assertTrue(config.isRequired());
		assertTrue(((CompanionConfig)config.getProgramConfig()).isRequired());
	}

	@Test
	public void testFailedFromDoesNotModifySource() {
		CompanionConfig source = newCompanionConfig(List.of("/bin/true"));
		source.setEnvironments(Map.of("MODE", "fast"));

		assertThrows(IllegalArgumentException.class,
					() -> ProgramCompanionConfig.from("bad.name", source, Map.of("MDT_INSTANCE_ID", "A")));
		assertEquals(Map.of("MODE", "fast"), source.getEnvironments());
	}

	@Test
	public void testCompanionUsesConfigAtInit() throws Exception {
		CompanionConfig programConfig = newCompanionConfig(List.of("/nonexistent/companion-program"));
		programConfig.setRequired(true);
		ProgramCompanionConfig config = ProgramCompanionConfig.from(null, programConfig, Map.of());

		m_companion = new ProgramCompanion();
		m_companion.init(null, config, null);

		// 초기화 이후의 설정 변경은 실행 동작과 로그에 반영되지 않아야 한다.
		config.setRequired(false);
		config.getProgramConfig().setCommandLine(List.of("/bin/true"));

		// required는 init 시점의 true로 동작하므로 시작 실패가 start()로 전달된다.
		assertThrows(EndpointException.class, () -> m_companion.start());

		// 실패 로그에는 실제로 실행한 명령이 나온다.
		ILoggingEvent error = awaitFailureLog();
		assertTrue(error.getFormattedMessage(), error.getFormattedMessage().contains("/nonexistent/companion-program"));
		assertFalse(error.getFormattedMessage(), error.getFormattedMessage().contains("/bin/true"));
	}

	private ILoggingEvent awaitFailureLog() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while ( true ) {
			// AppenderBase.doAppend()가 appender 객체로 동기화하므로 같은 객체로 잠그고 읽는다.
			synchronized ( m_appender ) {
				for ( ILoggingEvent ev: List.copyOf(m_appender.list) ) {
					if ( ev.getLevel() == Level.ERROR && ev.getFormattedMessage().startsWith("companion program failed") ) {
						return ev;
					}
				}
			}
			assertTrue("failure log did not arrive", System.nanoTime() < deadline);
			Thread.sleep(20);
		}
	}

	private static CompanionConfig newCompanionConfig(List<String> commandLine) {
		CompanionConfig config = new CompanionConfig();
		config.setCommandLine(commandLine);
		return config;
	}
}
