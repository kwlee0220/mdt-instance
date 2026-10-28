package mdt.endpoint.companion;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import com.google.common.util.concurrent.Service.State;

import mdt.config.ServiceEndpointConfigs;
import mdt.model.MDTModelSerDe;


/**
 * 여러 동반 프로그램({@code companions})의 설정 읽기, 이름 규칙, 독립 실행을 검증한다.
 * 실제 자식 프로세스({@code sleep})를 실행한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanionMultipleTest {
	private static final Map<String,String> INSTANCE_VARS = Map.of("MDT_INSTANCE_ID", "welder");

	private ProgramCompanion m_first;
	private ProgramCompanion m_second;

	@After
	public void tearDown() {
		for ( ProgramCompanion companion: new ProgramCompanion[] { m_first, m_second } ) {
			if ( companion != null ) {
				companion.stop();
			}
		}
	}

	@Test
	public void testCompanionsAreReadInOrder() throws Exception {
		String json = """
				{
				  "companion": { "commandLine": ["sleep", "1"] },
				  "companions": {
				    "predict": { "commandLine": ["sleep", "2"], "required": true },
				    "inspect": { "commandLine": ["sleep", "3"], "environments": { "MODE": "fast" } }
				  }
				}
				""";
		ServiceEndpointConfigs configs = MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class).readValue(json);

		assertEquals(List.of("sleep", "1"), configs.getCompanion().getCommandLine());
		assertEquals(List.of("predict", "inspect"), List.copyOf(configs.getCompanions().keySet()));
		assertTrue(configs.getCompanions().get("predict").isRequired());
		assertFalse(configs.getCompanions().get("inspect").isRequired());

		ProgramCompanionConfig inspect = ProgramCompanionConfig.from("inspect", configs.getCompanions().get("inspect"),
																	INSTANCE_VARS);
		assertEquals("inspect", inspect.getName());
		assertFalse(inspect.isRequired());
		assertEquals(Map.of("MDT_INSTANCE_ID", "welder", "MODE", "fast"),
					inspect.getProgramConfig().getEnvironments());

		ProgramCompanionConfig predict = ProgramCompanionConfig.from("predict", configs.getCompanions().get("predict"),
																	INSTANCE_VARS);
		assertTrue(predict.isRequired());
	}

	@Test
	public void testCompanionsAreOptional() throws Exception {
		ServiceEndpointConfigs configs = MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class)
						.readValue("{ \"companion\": { \"commandLine\": [\"sleep\", \"1\"] } }");
		assertTrue(configs.getCompanions().isEmpty());
	}

	@Test
	public void testInvalidNameIsRejected() {
		CompanionConfig companionConfig = newCompanionConfig(List.of("sleep", "1"));
		for ( String name: List.of("", "a.b", "has space", "-leading", "한글") ) {
			assertThrows(name, IllegalArgumentException.class,
						() -> ProgramCompanionConfig.from(name, companionConfig, INSTANCE_VARS));
		}
		assertEquals("inspect_waveform-2",
					ProgramCompanionConfig.from("inspect_waveform-2", companionConfig, INSTANCE_VARS).getName());
	}

	@Test
	public void testCompanionsRunIndependently() throws Exception {
		m_first = startCompanion("first");
		m_second = startCompanion("second");

		m_first.stop();

		assertEquals(State.TERMINATED, m_first.getProgramState());
		assertFalse(m_first.isShutdownHookRegistered());
		assertEquals(State.RUNNING, m_second.getProgramState());
		assertTrue(m_second.isShutdownHookRegistered());
	}

	private static CompanionConfig newCompanionConfig(List<String> commandLine) {
		CompanionConfig companionConfig = new CompanionConfig();
		companionConfig.setCommandLine(commandLine);
		return companionConfig;
	}

	private static ProgramCompanion startCompanion(String name) throws Exception {
		ProgramCompanionConfig config = ProgramCompanionConfig.from(name, newCompanionConfig(List.of("sleep", "30")),
																	INSTANCE_VARS);
		ProgramCompanion companion = new ProgramCompanion();
		companion.init(null, config, null);
		companion.start();

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while ( companion.getProgramState() != State.RUNNING ) {
			assertTrue("program did not reach RUNNING", System.nanoTime() < deadline);
			Thread.sleep(20);
		}
		return companion;
	}
}
