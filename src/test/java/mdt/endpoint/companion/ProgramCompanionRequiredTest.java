package mdt.endpoint.companion;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.google.common.util.concurrent.Service.State;

import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;

import mdt.config.ServiceEndpointConfigs;
import mdt.model.MDTModelSerDe;


/**
 * 필수 동반 프로그램({@code required})의 시작 동작과 설정 읽기를 검증한다.
 * 실제 자식 프로세스({@code sleep})를 실행한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanionRequiredTest {
	private ProgramCompanion m_companion;

	@After
	public void tearDown() {
		if ( m_companion != null ) {
			m_companion.stop();
		}
	}

	@Test
	public void testRequiredStartFailureFailsStart() {
		m_companion = initCompanion(List.of("/nonexistent/companion-program"), true);

		EndpointException e = assertThrows(EndpointException.class, () -> m_companion.start());
		assertTrue(e.getMessage(), e.getMessage().contains("name=companion"));
		assertNotNull(e.getCause());
		assertEquals(State.FAILED, m_companion.getProgramState());
	}

	@Test
	public void testRequiredStartWaitsForRunning() throws Exception {
		m_companion = initCompanion(List.of("sleep", "30"), true);

		m_companion.start();

		// start()가 반환되었을 때 이미 RUNNING이어야 한다.
		assertEquals(State.RUNNING, m_companion.getProgramState());
	}

	@Test
	public void testOptionalStartFailureDoesNotFailStart() throws Exception {
		m_companion = initCompanion(List.of("/nonexistent/companion-program"), false);

		// 필수가 아니면 시작 실패가 start()로 전달되지 않는다(ERROR 로그만 남는다).
		m_companion.start();
	}

	@Test
	public void testRequiredIsReadFromConfig() throws Exception {
		ServiceEndpointConfigs configs = MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class)
				.readValue("{ \"companion\": { \"commandLine\": [\"sleep\", \"1\"], \"required\": true } }");
		assertTrue(configs.getCompanion().isRequired());
		assertEquals(List.of("sleep", "1"), configs.getCompanion().getCommandLine());

		configs = MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class)
						.readValue("{ \"companion\": { \"commandLine\": [\"sleep\", \"1\"] } }");
		assertFalse(configs.getCompanion().isRequired());
	}

	private static ProgramCompanion initCompanion(List<String> commandLine, boolean required) {
		CompanionConfig programConfig = new CompanionConfig();
		programConfig.setCommandLine(commandLine);
		programConfig.setRequired(required);

		ProgramCompanionConfig config = new ProgramCompanionConfig();
		config.setProgramConfig(programConfig);
		config.setRequired(programConfig.isRequired());

		ProgramCompanion companion = new ProgramCompanion();
		companion.init(null, config, null);
		return companion;
	}
}
