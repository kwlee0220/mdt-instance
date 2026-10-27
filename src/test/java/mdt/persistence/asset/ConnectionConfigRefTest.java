package mdt.persistence.asset;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.StringWriter;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import utils.jdbc.JdbcConfiguration;

import mdt.ConnectionConfigRef;
import mdt.persistence.asset.jdbc.SimpleJdbcAssetVariableConfig;
import mdt.persistence.asset.robot.RobotStateVariableConfig;


/**
 * {@link ConnectionConfigRef}와, 이를 사용하는 JDBC·로봇 트윈 AssetVariable 설정의
 * 연결 설정 필드(이름 또는 객체) 처리를 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ConnectionConfigRefTest {
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@Test
	public void testLoadForms() throws Exception {
		ConnectionConfigRef<JdbcConfiguration> dflt = load("{}");
		assertEquals("default", dflt.name());
		assertNull(dflt.inline());

		assertEquals("factory", load("{ \"jdbcConfig\": \"factory\" }").name());
		assertEquals("factory", load("{ \"jdbcConfigName\": \"factory\" }").name());
		assertEquals("default", load("{ \"jdbcConfig\": null }").name());

		ConnectionConfigRef<JdbcConfiguration> inline = load("""
			{ "jdbcConfig": { "jdbcUrl": "jdbc:postgresql://localhost:5432/mdt", "user": "mdt" } }
			""");
		assertNull(inline.name());
		assertEquals("jdbc:postgresql://localhost:5432/mdt", inline.inline().getJdbcUrl());
		// 인라인 설정은 전역 설정을 조회하지 않는다.
		assertSame(inline.inline(), inline.resolve(name -> { throw new AssertionError("global lookup"); }));
	}

	@Test
	public void testInvalidForms() throws Exception {
		assertThrows(IOException.class, () -> load("{ \"jdbcConfig\": \"a\", \"jdbcConfigName\": \"b\" }"));
		assertThrows(IOException.class, () -> load("{ \"jdbcConfig\": 10 }"));
		assertThrows(IOException.class, () -> load("{ \"jdbcConfigName\": { } }"));
		assertThrows(IllegalArgumentException.class, () -> new ConnectionConfigRef<>(null, null));
		assertThrows(IllegalArgumentException.class, () -> new ConnectionConfigRef<>("a", "b"));
	}

	@Test
	public void testDisplayStringDoesNotResolve() throws Exception {
		assertEquals("jdbcs.factory", load("{ \"jdbcConfig\": \"factory\" }").toDisplayString("jdbcs", c -> "x"));
		assertEquals("(inline)", load("{ \"jdbcConfig\": { \"jdbcUrl\": \"jdbc:h2:mem:t\" } }")
									.toDisplayString("jdbcs", c -> "(inline)"));
	}

	@Test
	public void testJdbcVariableConfig() throws Exception {
		SimpleJdbcAssetVariableConfig byName = SimpleJdbcAssetVariableConfig.deserializeFields(MAPPER.readTree("""
			{ "element": "Data:Status", "jdbcConfig": "factory", "readQuery": "select 1" }
			"""));
		JsonNode written = serialize(byName);
		assertEquals("factory", written.get("jdbcConfig").asText());
		assertFalse(written.has("jdbcConfigName"));
		assertTrue(byName.toString(), byName.toString().contains("jdbcs.factory"));

		// 이전 형식의 jdbcConfigName으로 읽은 설정도 jdbcConfig 하나로 기록한다.
		SimpleJdbcAssetVariableConfig legacy = SimpleJdbcAssetVariableConfig.deserializeFields(MAPPER.readTree("""
			{ "element": "Data:Status", "jdbcConfigName": "factory", "readQuery": "select 1" }
			"""));
		assertEquals("factory", serialize(legacy).get("jdbcConfig").asText());

		SimpleJdbcAssetVariableConfig inline = SimpleJdbcAssetVariableConfig.deserializeFields(MAPPER.readTree("""
			{ "element": "Data:Status", "readQuery": "select 1",
			  "jdbcConfig": { "jdbcUrl": "jdbc:postgresql://db/mdt?password=secret" } }
			"""));
		JsonNode inlineWritten = serialize(inline);
		assertEquals("jdbc:postgresql://db/mdt?password=secret", inlineWritten.get("jdbcConfig").get("jdbcUrl").asText());
		assertEquals("jdbc:postgresql://db/mdt?password=secret",
					SimpleJdbcAssetVariableConfig.deserializeFields(inlineWritten).getJdbcConfig().getJdbcUrl());
		// 인라인 URL에는 비밀번호가 있을 수 있으므로 toString()에 출력하지 않는다.
		assertFalse(inline.toString(), inline.toString().contains("secret"));
	}

	@Test
	public void testRobotStateVariableConfig() throws Exception {
		RobotStateVariableConfig byName = RobotStateVariableConfig.deserializeFields(MAPPER.readTree("""
			{ "element": "param:JointStates", "variable": "joint_states", "robotTwinConfig": "panda" }
			"""));
		assertEquals("panda", byName.getRobotTwinConfigName());
		// toString()은 전역 설정 파일을 읽지 않는다 (전역 설정 파일이 지정되지 않은 상태).
		assertTrue(byName.toString(), byName.toString().contains("robotTwins.panda"));
		JsonNode written = serialize(byName);
		assertEquals("panda", written.get("robotTwinConfig").asText());
		assertFalse(written.has("robotTwinConfigName"));

		RobotStateVariableConfig legacy = RobotStateVariableConfig.deserializeFields(MAPPER.readTree("""
			{ "element": "param:JointStates", "variable": "joint_states", "robotTwinConfigName": "panda" }
			"""));
		assertEquals("panda", legacy.getRobotTwinConfigName());

		assertThrows(IOException.class, () -> RobotStateVariableConfig.deserializeFields(MAPPER.readTree("""
			{ "element": "param:JointStates", "variable": "joint_states",
			  "robotTwinConfig": "panda", "robotTwinConfigName": "panda" }
			""")));
	}

	private static ConnectionConfigRef<JdbcConfiguration> load(String json) throws Exception {
		return ConnectionConfigRef.load(MAPPER.readTree(json), "jdbcConfig", "jdbcConfigName",
										JdbcConfiguration.class);
	}

	private static JsonNode serialize(AssetVariableConfig config) throws Exception {
		StringWriter writer = new StringWriter();
		try ( JsonGenerator gen = MAPPER.getFactory().createGenerator(writer) ) {
			gen.writeStartObject();
			config.serializeFields(gen);
			gen.writeEndObject();
		}
		return MAPPER.readTree(writer.toString());
	}
}
