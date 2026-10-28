package mdt.endpoint.companion;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import utils.async.command.ProgramServiceConfig;


/**
 * {@link ProgramCompanion#init}이 잘못된 설정을 명확한 메시지로 거부하는지와 hook 이름 형식을 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanionInitTest {
	@Test
	public void testMissingProgramConfigIsRejected() {
		ProgramCompanion companion = new ProgramCompanion();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
												() -> companion.init(null, new ProgramCompanionConfig(), null));
		assertTrue(e.getMessage(), e.getMessage().contains("programConfig"));

		// 실패한 init은 상태를 남기지 않으므로 올바른 설정으로 다시 초기화할 수 있다.
		ProgramServiceConfig programConfig = new ProgramServiceConfig();
		programConfig.setCommandLine(List.of("true"));
		ProgramCompanionConfig config = new ProgramCompanionConfig();
		config.setProgramConfig(programConfig);
		companion.init(null, config, null);
		assertSame(config, companion.asConfig());
		companion.stop();
	}

	@Test
	public void testNullConfigIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new ProgramCompanion().init(null, null, null));
	}

	@Test
	public void testHookName() {
		assertEquals("robot-01-companion", ProgramCompanion.toHookName("robot-01", null));
		assertEquals("companion", ProgramCompanion.toHookName("", null));
		assertEquals("companion", ProgramCompanion.toHookName(null, null));
		assertEquals("robot-01-companion-inspect", ProgramCompanion.toHookName("robot-01", "inspect"));
		assertEquals("companion-inspect", ProgramCompanion.toHookName(null, "inspect"));
	}

	@Test
	public void testToStringWithoutProgramConfig() {
		assertEquals("ProgramCompanionConfig[programConfig=null]", new ProgramCompanionConfig().toString());
	}
}
