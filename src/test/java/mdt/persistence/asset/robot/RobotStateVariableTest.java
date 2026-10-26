package mdt.persistence.asset.robot;

import static org.junit.Assert.*;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementCollection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import utils.json.JacksonDeserializationException;

import mdt.ElementLocations;
import mdt.persistence.asset.AssetVariableException;
import mdt.robot.client.TwinException;

/**
 * RobotStateVariable이 mdt-robot-client의 RobotTwinClient로 상태 변수를 읽고 mapping대로 반영하는지 확인한다.
 */
public class RobotStateVariableTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PATH = "/api/v1/robot_twins/panda01/variables/ee_pose";

	private MockWebServer server;
	private volatile MockResponse response;

	@Before
	public void setUp() throws Exception {
		server = new MockWebServer();
		server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
			@Override public MockResponse dispatch(RecordedRequest request) {
				return PATH.equals(request.getPath()) ? response : json(404, Map.of("error",
						Map.of("code", "NOT_FOUND", "message", request.getPath())));
			}
		});
		server.start();
		response = eePose("OK", 0.4);
	}

	@After public void tearDown() throws Exception { server.shutdown(); }

	@Test
	public void readsThroughRobotTwinClientAndAppliesMappings() throws Exception {
		RobotStateVariable variable = variable();
		SubmodelElementCollection read = (SubmodelElementCollection)variable.read();
		assertEquals("0.4", component(read, "x"));
		assertEquals("0.1", component(read, "z"));
		assertEquals("y has no mapping and keeps its value", "0", component(read, "y"));
		RecordedRequest request = server.takeRequest();
		assertEquals("GET", request.getMethod());
		assertEquals(PATH, request.getPath());
	}

	@Test
	public void lowQualityIsWarnedButAppliedByDefault() throws Exception {
		// 품질 판정은 이 클래스가 하므로 RobotTwinClient.readRaw()가 낮은 품질을 예외로 바꾸지 않아야 한다.
		response = eePose("STALE", 0.7);
		assertEquals("0.7", component((SubmodelElementCollection)variable().read(), "x"));
	}

	@Test
	public void missingValueKeepsPreviousValue() throws Exception {
		RobotStateVariable variable = variable();
		assertEquals("0.4", component((SubmodelElementCollection)variable.read(), "x"));
		response = json(200, Map.of("quality", "NO_DATA", "reason", "no message yet"));
		assertEquals("0.4", component((SubmodelElementCollection)variable.read(), "x"));
	}

	@Test
	public void httpErrorIsWrappedWithTwinException() throws Exception {
		response = json(404, Map.of("error", Map.of("code", "UNKNOWN_VARIABLE", "message", "no such variable")));
		AssetVariableException e = assertThrows(AssetVariableException.class, () -> variable().read());
		assertTrue(e.getMessage(), e.getMessage().contains(PATH));
		TwinException cause = (TwinException)e.getCause();
		assertEquals(404, cause.getHttpStatus());
	}

	@Test
	public void invalidBaseUrlIsRejectedAtConstruction() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> new RobotStateVariable(config("ftp://127.0.0.1:1")));
	}

	// 검토 1: requireOkQuality를 읽고 쓴다.
	@Test
	public void requireOkQualityIsLoadedAppliedAndSerialized() throws Exception {
		response = eePose("STALE", 0.7);
		RobotStateVariableConfig config = config(server.url("/").toString(), Map.of("requireOkQuality", true));
		assertTrue(config.isRequireOkQuality());
		AssetVariableException e = assertThrows(AssetVariableException.class, () -> variable(config).read());
		assertTrue(e.getMessage(), e.getMessage().contains("quality=STALE"));

		assertTrue(serialize(config).path("requireOkQuality").asBoolean());
		assertTrue(RobotStateVariableConfig.deserializeFields(serialize(config)).isRequireOkQuality());
		assertFalse(serialize(config(server.url("/").toString())).has("requireOkQuality"));
		assertThrows(JacksonDeserializationException.class,
					() -> config(server.url("/").toString(), Map.of("requireOkQuality", "yes")));
	}

	// 검토 2: 일부 mapping이 실패하면 캐시를 바꾸지 않는다.
	@Test
	public void failedMappingLeavesCacheUnchanged() throws Exception {
		RobotStateVariable variable = variable();
		response = json(200, Map.of("quality", "OK", "value", Map.of("pose", Map.of("position",
				Map.of("x", 0.9, "y", 0.0, "z", "bad")))));
		assertThrows(AssetVariableException.class, variable::read);
		response = json(200, Map.of("quality", "NO_DATA", "reason", "no message yet"));
		SubmodelElementCollection read = (SubmodelElementCollection)variable.read();
		assertEquals("x must not keep the value of the failed read", "0", component(read, "x"));
	}

	// 검토 3: 숫자 Property에 숫자가 아닌 값이 오면 0으로 바꾸지 않고 실패한다.
	@Test
	public void nonNumericValueForNumericPropertyFails() throws Exception {
		RobotStateVariable variable = variable();
		assertEquals("0.4", component((SubmodelElementCollection)variable.read(), "x"));
		response = json(200, Map.of("quality", "OK", "value", Map.of("pose", Map.of("position",
				Map.of("x", "sensor-error", "y", 0.0, "z", 0.1)))));
		AssetVariableException e = assertThrows(AssetVariableException.class, variable::read);
		assertTrue(e.getMessage(), e.getMessage().contains("sensor-error"));
		response = json(200, Map.of("quality", "NO_DATA"));
		assertEquals("0.4", component((SubmodelElementCollection)variable.read(), "x"));
	}

	// 검토 4: mapping 값은 문자열 JSON Pointer여야 한다.
	@Test
	public void invalidMappingsAreRejectedWhenLoading() throws Exception {
		String url = server.url("/").toString();
		assertThrows(JacksonDeserializationException.class, () -> config(url, Map.of("mappings",
				Map.of("Position.x", Map.of("pointer", "/pose/position/x")))));
		assertThrows(JacksonDeserializationException.class, () -> config(url, Map.of("mappings",
				Map.of("Position.x", "pose/position/x"))));
		assertThrows(JacksonDeserializationException.class, () -> config(url, Map.of("mappings",
				List.of(Map.of("subPath", "Position.x", "pointer", 3)))));
		// 이전 버전의 배열 형식과 빈 포인터(변수 값 전체)는 계속 받는다.
		assertEquals(1, config(url, Map.of("mappings", List.of(Map.of("subPath", "Position.x",
				"pointer", "/pose/position/x")))).getMappings().size());
		assertEquals("", config(url, Map.of("mappings", Map.of("", ""))).getMappings().get(0).pointer());
		assertThrows(IllegalArgumentException.class,
					() -> new RobotStateVariableConfig.SubPathToJsonPointer("Position.x", "pose/x"));
	}

	// 검토 5: 공개 생성자로 만든 설정은 전역 설정의 default 항목을 쓴다.
	@Test
	public void publicConstructorUsesDefaultConnectionName() {
		RobotStateVariableConfig config = new RobotStateVariableConfig(ElementLocations.parseStringExpr("param:EEPose"),
																		null, "ee_pose", List.of());
		assertEquals("default", config.getRobotTwinConfigName());
		assertFalse(config.isRequireOkQuality());
	}

	// 재검토 1: 컬렉션 단위 매핑도 내부 Property의 값을 검사한다.
	@Test
	public void collectionMappingIsCheckedRecursively() throws Exception {
		RobotStateVariable variable = variable(config(server.url("/").toString(),
												Map.of("mappings", Map.of("Position", "/pose/position"))));
		SubmodelElementCollection read = (SubmodelElementCollection)variable.read();
		assertEquals("0.4", component(read, "x"));
		assertEquals("0.1", component(read, "z"));

		response = json(200, Map.of("quality", "OK", "value", Map.of("pose", Map.of("position",
				Map.of("x", "sensor-error", "y", 0.0, "z", 0.2)))));
		AssetVariableException e = assertThrows(AssetVariableException.class, variable::read);
		assertTrue(e.getMessage(), e.getMessage().contains("element=Position.x"));
		response = json(200, Map.of("quality", "NO_DATA"));
		assertEquals("0.1", component((SubmodelElementCollection)variable.read(), "z"));
	}

	// 재검토 2: 실수는 유한해야 한다. JSON의 1e400은 Jackson에서 Infinity가 된다.
	@Test
	public void infiniteDoubleIsRejected() throws Exception {
		RobotStateVariable variable = variable(config(server.url("/").toString()), DataTypeDefXsd.DOUBLE);
		response = rawJson("{\"quality\":\"OK\",\"value\":{\"pose\":{\"position\":{\"x\":1e400,\"y\":0,\"z\":0.1}}}}");
		AssetVariableException e = assertThrows(AssetVariableException.class, variable::read);
		assertTrue(e.getMessage(), e.getMessage().contains("DOUBLE"));
		response = eePose("OK", 0.5);
		assertEquals("0.5", component((SubmodelElementCollection)variable.read(), "x"));
	}

	// 재검토 4: DECIMAL은 double 범위를 넘는 정수도 받고, Infinity가 된 실수만 거부한다.
	@Test
	public void decimalAcceptsHugeIntegersButNotInfinity() throws Exception {
		RobotStateVariable variable = variable(config(server.url("/").toString()), DataTypeDefXsd.DECIMAL);
		String huge = "1" + "0".repeat(309);
		response = rawJson("{\"quality\":\"OK\",\"value\":{\"pose\":{\"position\":{\"x\":" + huge + ",\"y\":0,\"z\":0.1}}}}");
		assertEquals(huge, component((SubmodelElementCollection)variable.read(), "x"));

		response = rawJson("{\"quality\":\"OK\",\"value\":{\"pose\":{\"position\":{\"x\":1e400,\"y\":0,\"z\":0.1}}}}");
		AssetVariableException e = assertThrows(AssetVariableException.class, variable::read);
		assertTrue(e.getMessage(), e.getMessage().contains("DECIMAL"));
		response = json(200, Map.of("quality", "NO_DATA"));
		assertEquals(huge, component((SubmodelElementCollection)variable.read(), "x"));
	}

	// 재검토 3: '~' 뒤에는 0 또는 1만 올 수 있다(RFC 6901).
	@Test
	public void invalidPointerEscapesAreRejectedWhenLoading() throws Exception {
		String url = server.url("/").toString();
		for ( String pointer: List.of("/pose~2/position/x", "/pose/position/x~", "/pose/~/x") ) {
			assertThrows(pointer, JacksonDeserializationException.class,
						() -> config(url, Map.of("mappings", Map.of("Position.x", pointer))));
		}
		assertEquals("/a~0b~1c", config(url, Map.of("mappings", Map.of("Position.x", "/a~0b~1c"))).getMappings().get(0).pointer());
	}

	private RobotStateVariable variable() throws Exception {
		return variable(config(server.url("/").toString()));
	}

	private static RobotStateVariable variable(RobotStateVariableConfig config) {
		return variable(config, DataTypeDefXsd.FLOAT);
	}

	private static RobotStateVariable variable(RobotStateVariableConfig config, DataTypeDefXsd type) {
		SubmodelElementCollection prototype = collection("EEPose", collection("Position",
				property("x", "0", type), property("y", "0", type), property("z", "0", type)));
		return new RobotStateVariable(config) {
			{ m_prototype = prototype; }
		};
	}

	private static RobotStateVariableConfig config(String baseUrl) throws Exception {
		return config(baseUrl, Map.of());
	}

	private static RobotStateVariableConfig config(String baseUrl, Map<String,?> overrides) throws Exception {
		Map<String,Object> fields = new HashMap<>(Map.of(
				"element", "param:EEPose",
				"variable", "ee_pose",
				"robotTwinConfig", Map.of("robotTwinBaseUrl", baseUrl, "twinId", "panda01"),
				"mappings", Map.of("Position.x", "/pose/position/x", "Position.z", "/pose/position/z")));
		fields.putAll(overrides);
		return RobotStateVariableConfig.deserializeFields(JSON.valueToTree(fields));
	}

	private static JsonNode serialize(RobotStateVariableConfig config) throws Exception {
		StringWriter out = new StringWriter();
		try ( JsonGenerator gen = JSON.createGenerator(out) ) {
			gen.writeStartObject();
			config.serializeFields(gen);
			gen.writeEndObject();
		}
		return JSON.readTree(out.toString());
	}

	private static MockResponse eePose(String quality, double x) {
		return json(200, Map.of("quality", quality, "received_at", "2026-10-25T09:00:00Z",
				"value", Map.of("header", Map.of("frame_id", "panda_link0"),
						"pose", Map.of("position", Map.of("x", x, "y", 0.0, "z", 0.1),
								"orientation", Map.of("x", 1.0, "y", 0.0, "z", 0.0, "w", 0.0)))));
	}

	private static String component(SubmodelElementCollection pose, String axis) {
		SubmodelElementCollection position = (SubmodelElementCollection)pose.getValue().stream()
				.filter(e -> e.getIdShort().equals("Position")).findFirst().orElseThrow();
		return position.getValue().stream().filter(e -> e.getIdShort().equals(axis))
				.map(e -> ((Property)e).getValue()).findFirst().orElseThrow();
	}

	private static SubmodelElementCollection collection(String name, SubmodelElement... children) {
		return new DefaultSubmodelElementCollection.Builder().idShort(name).value(new ArrayList<>(List.of(children))).build();
	}

	private static Property property(String name, String value, DataTypeDefXsd type) {
		return new DefaultProperty.Builder().idShort(name).valueType(type).value(value).build();
	}

	private static MockResponse rawJson(String body) {
		return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
	}

	private static MockResponse json(int status, Object body) {
		try {
			return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
									.setBody(JSON.writeValueAsString(body));
		}
		catch ( Exception e ) { throw new AssertionError(e); }
	}
}
