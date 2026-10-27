package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import mdt.MDTGlobalConfigurations;
import mdt.assetconnection.operation.JavaOperationProviderConfig;


/**
 * 로봇 트윈 연산 Provider의 {@code robotTwinConfig} 인자(전역 설정 이름 또는 접속 정보 객체) 처리를 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RobotTwinProviderConfigTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private Path m_globalConfig;

	@Before
	public void setUp() throws Exception {
		m_globalConfig = Files.createTempFile("mdt_global_config", ".json");
		Files.writeString(m_globalConfig, """
			{ "robotTwins": {
			    "panda": { "robotTwinBaseUrl": "http://panda:8801", "twinId": "panda01" },
			    "ur5":   { "robotTwinBaseUrl": "http://ur5:8801", "twinId": "ur501" } } }
			""");
		MDTGlobalConfigurations.setGlobalConfigFile(m_globalConfig.toFile());
	}

	@After
	public void tearDown() throws Exception {
		MDTGlobalConfigurations.setGlobalConfigFile(null);
		Files.deleteIfExists(m_globalConfig);
	}

	@Test
	public void testConfigByName() throws Exception {
		assertEquals("ur501", provider(Map.of("robotTwinConfig", "ur5")).m_rtConfig.getTwinId());
	}

	@Test
	public void testLegacyConfigName() throws Exception {
		assertEquals("ur501", provider(Map.of("robotTwinConfigName", "ur5")).m_rtConfig.getTwinId());
	}

	@Test
	public void testInlineConfig() throws Exception {
		ResetSceneProvider provider = provider(Map.of("robotTwinConfig",
												Map.of("robotTwinBaseUrl", "http://inline:8801", "twinId", "inline01")));
		assertEquals("inline01", provider.m_rtConfig.getTwinId());
		assertEquals("http://inline:8801", provider.m_rtConfig.getRobotTwinBaseUrl());
	}

	@Test
	public void testDefaultConfig() throws Exception {
		// 인자를 생략하면 전역 설정의 default 항목을 사용하며, default가 없으면 그룹의 첫 항목을 사용한다.
		assertEquals("panda01", provider(Map.of()).m_rtConfig.getTwinId());
	}

	@Test
	public void testNameAndLegacyNameTogether() {
		assertThrows(ConfigurationInitializationException.class,
					() -> provider(Map.of("robotTwinConfig", "ur5", "robotTwinConfigName", "panda")));
	}

	private static ResetSceneProvider provider(Map<String,Object> args) throws Exception {
		Map<String,JsonNode> argNodes = JSON.convertValue(args, JSON.getTypeFactory()
																.constructMapType(Map.class, String.class, JsonNode.class));
		JavaOperationProviderConfig config = new JavaOperationProviderConfig(ResetSceneProvider.class.getName(),
																			argNodes);
		return new ResetSceneProvider(null, null, config);
	}
}
