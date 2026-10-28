package mdt.endpoint.companion;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;


/**
 * {@link ProgramCompanion#mergeEnvironments(Map, Map)}의 환경 변수 병합 규칙을 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanionTest {
	private static final Map<String,String> INSTANCE_VARS = Map.of("MDT_INSTANCE_ID", "welder",
																	"MDT_URL", "http://localhost:12985");
	
	private Logger m_logger;
	private ListAppender<ILoggingEvent> m_appender;
	
	@Before
	public void setUp() {
		m_logger = (Logger)LoggerFactory.getLogger(ProgramCompanion.class);
		m_appender = new ListAppender<>();
		m_appender.start();
		m_logger.addAppender(m_appender);
	}
	
	@After
	public void tearDown() {
		m_logger.detachAppender(m_appender);
	}

	@Test
	public void testUserVariablesAreAdded() {
		Map<String,String> merged = ProgramCompanion.mergeEnvironments(INSTANCE_VARS, Map.of("DB_URL", "jdbc:x"));
		assertEquals(Map.of("MDT_INSTANCE_ID", "welder", "MDT_URL", "http://localhost:12985", "DB_URL", "jdbc:x"),
					merged);
		assertTrue(warnings().isEmpty());
	}

	@Test
	public void testUserValueOverridesInstanceVariableWithWarning() {
		Map<String,String> merged = ProgramCompanion.mergeEnvironments(INSTANCE_VARS,
																	Map.of("MDT_URL", "http://proxy:8080"));
		assertEquals("http://proxy:8080", merged.get("MDT_URL"));
		assertEquals("welder", merged.get("MDT_INSTANCE_ID"));
		
		// 경고에는 변수 이름만 있고 값은 없다.
		List<String> warnings = warnings();
		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0), warnings.get(0).contains("MDT_URL"));
		assertFalse(warnings.get(0), warnings.get(0).contains("proxy"));
	}

	@Test
	public void testSameValueDoesNotWarn() {
		ProgramCompanion.mergeEnvironments(INSTANCE_VARS, Map.of("MDT_INSTANCE_ID", "welder"));
		assertTrue(warnings().isEmpty());
	}

	@Test
	public void testInputsAreNotModified() {
		Map<String,String> user = new HashMap<>(Map.of("A", "1"));
		Map<String,String> merged = ProgramCompanion.mergeEnvironments(INSTANCE_VARS, user);
		merged.put("B", "2");
		assertEquals(Map.of("A", "1"), user);
		assertEquals(2, INSTANCE_VARS.size());
	}

	@Test
	public void testNullUserVariables() {
		assertEquals(INSTANCE_VARS, ProgramCompanion.mergeEnvironments(INSTANCE_VARS, null));
	}
	
	private List<String> warnings() {
		return m_appender.list.stream()
							.filter(ev -> ev.getLevel() == Level.WARN)
							.map(ILoggingEvent::getFormattedMessage)
							.toList();
	}
}
