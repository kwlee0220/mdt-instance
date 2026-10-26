package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultKey;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;

import mdt.assetconnection.operation.MDTOperationProvider;
import mdt.assetconnection.operation.MDTOperationProviderConfig;
import mdt.robot.client.Geometry;
import mdt.robot.client.Quaternion;
import mdt.rt.Ros2SerDes;

/**
 * 가짜 트윈의 단순한 물리 모형(peg 바닥점 = 도구 기준점 + 파지 오프셋 + 이동마다 정한 흔들림)으로
 * AlignHeldObjectProvider의 입출력 변환을 검증한다. 회차별 오차는 |drift[k] - drift[k-1]|이다.
 */
public class AlignHeldObjectProviderTest {
	private static final String BATCH = "/variables?names=ee_pose,scene_objects";
	private static final double[] ENTRY = { 0.5, -0.1, 0.028 };
	private static final double[] HELD = { 0.003, -0.002, -0.054 };

	private TwinServer server;
	private AlignHeldObjectProvider provider;
	private Map<String, SubmodelElement> inputs;
	private Map<String, SubmodelElement> out;
	// 아래 모형 상태는 this로 보호한다.
	private double[] m_tcp = { 0.49, -0.095, 0.15 };
	private double[] m_drifts = { 0.0 };
	private int m_moves;
	private boolean m_invalidName;	// AAS idShort로 쓸 수 없는 이름의 물체를 장면에 더한다.
	private final List<JsonNode> m_commands = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		server = new TwinServer("move_linear");
		server.on("GET", "/variables/scene_objects", request -> json(200, sceneEnvelope()));
		server.on("GET", BATCH, request -> json(200, Map.of("values", Map.of(
				"ee_pose", Map.of("quality", "OK", "received_at", Instant.now().toString(), "value", eeValue()),
				"scene_objects", sceneEnvelope()))));
		server.on("POST", "/operations/move_linear", request -> {
			try {
				JsonNode args = JSON.readTree(request.getBody().readUtf8()).path("inputs");
				Map<String,Object> finalPose;
				synchronized ( this ) {
					m_commands.add(args);
					JsonNode p = args.path("pose").path("position");
					m_tcp = new double[] { p.path("x").asDouble(), p.path("y").asDouble(), p.path("z").asDouble() };
					++m_moves;
					finalPose = Map.of("pose", Map.of("position", position(m_tcp), "orientation", args.path("pose").path("orientation")));
				}
				return json(200, Map.of("status", "COMPLETED", "outputs", Map.of("final_pose", finalPose, "closed_loop", true)));
			}
			catch ( Exception e ) { throw new AssertionError(e); }
		});
		server.start();
		provider = new AlignHeldObjectProvider(null, null, server.config(AlignHeldObjectProvider.class));
		inputs = new LinkedHashMap<>();
		inputs.put("ObjectName", property("ObjectName", DataTypeDefXsd.STRING, "peg"));
		inputs.put("Entry", collection("Entry", property("x", DataTypeDefXsd.DOUBLE, "" + ENTRY[0]),
				property("y", DataTypeDefXsd.DOUBLE, "" + ENTRY[1]), property("z", DataTypeDefXsd.DOUBLE, "" + ENTRY[2])));
		input("Clearance", DataTypeDefXsd.DOUBLE, "0.0005");	// 허용 반경 = 0.5 mm × 0.4 = 0.2 mm
		input("TravelZ", DataTypeDefXsd.DOUBLE, "0.15");
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "0");
		out = new LinkedHashMap<>();
		out.put("Aligned", property("Aligned", DataTypeDefXsd.BOOLEAN, "old"));
		out.put("StopReason", property("StopReason", DataTypeDefXsd.STRING, "old"));
		out.put("Passes", property("Passes", DataTypeDefXsd.INT, "-1"));
		out.put("ErrorM", property("ErrorM", DataTypeDefXsd.DOUBLE, "99"));
		out.put("Target", pose("Target", -1));
		out.put("Objects", collection("Objects", collection("oldObject")));
		out.put("FailedStage", property("FailedStage", DataTypeDefXsd.STRING, "old"));
		out.put("FailureReason", property("FailureReason", DataTypeDefXsd.STRING, "old"));
	}

	@After public void tearDown() throws Exception { server.close(); }

	@Test(timeout = 10000)
	public void alignedResultPublishesTargetErrorAndObservedScene() throws Exception {
		drifts(0, 0.0001);
		invoke();
		assertEquals("true", value("Aligned"));
		assertEquals("ALIGNED", value("StopReason"));
		assertEquals("1", value("Passes"));
		assertEquals(0.0001, Double.parseDouble(value("ErrorM")), 1e-9);
		assertEquals("", value("FailedStage"));
		assertEquals("", value("FailureReason"));
		JsonNode target = Ros2SerDes.POSE_SERDE.toJsonNode(out.get("Target"));
		// 첫 보정 목표 = 입구 - 파지 오프셋 - drift[0], 높이는 TravelZ.
		assertEquals(ENTRY[0] - HELD[0], target.path("position").path("x").asDouble(), 1e-6);
		assertEquals(ENTRY[1] - HELD[1], target.path("position").path("y").asDouble(), 1e-6);
		assertEquals(0.15, target.path("position").path("z").asDouble(), 1e-6);
		assertEquals("peg", ((SubmodelElementCollection)out.get("Objects")).getValue().get(0).getIdShort());
	}

	@Test(timeout = 10000)
	public void unconvertibleSceneNameKeepsObjectsButReturnsAlignment() throws Exception {
		synchronized ( this ) { m_invalidName = true; }
		drifts(0, 0.0001);
		invoke();
		assertEquals("true", value("Aligned"));
		assertEquals("1", value("Passes"));
		assertEquals("oldObject", ((SubmodelElementCollection)out.get("Objects")).getValue().get(0).getIdShort());
	}

	@Test(timeout = 10000)
	public void defaultsSendAbsoluteDownwardYawAndSlowCorrection() throws Exception {
		drifts(0, 0.0001);
		invoke();
		Quaternion expected = Geometry.downWithYaw(Math.toRadians(-90));
		synchronized ( this ) {
			JsonNode command = m_commands.get(0);
			assertEquals("0.15", command.path("velocity_scaling").asText());
			assertEquals(expected.x(), command.path("pose").path("orientation").path("x").asDouble(), 1e-12);
			assertEquals(expected.y(), command.path("pose").path("orientation").path("y").asDouble(), 1e-12);
		}
	}

	@Test(timeout = 10000)
	public void unalignedResultIsReturnedNormallyWithDiagnostics() throws Exception {
		drifts(0, 0.0003, 0.0006);
		invoke();
		assertEquals("false", value("Aligned"));
		assertEquals("NOT_IMPROVING", value("StopReason"));
		assertEquals("2", value("Passes"));
		assertFalse(value("FailureReason").isBlank());
	}

	@Test(timeout = 10000)
	public void frameworkReturnsDiagnosticsForUnalignedResult() throws Exception {
		drifts(0, 0.0003, 0.0006);
		OperationVariable[] result = frameworkProvider().invoke(variables(inputs), new OperationVariable[0]);
		assertEquals("false", value(result, "Aligned"));
		assertEquals("NOT_IMPROVING", value(result, "StopReason"));
		// 프레임워크가 복제한 결과를 반환했는지 확인한다. 원본 출력 바인딩은 그대로다.
		assertEquals("old", value("StopReason"));
	}

	@Test
	public void invalidInputsAreRejectedBeforeCommunication() throws Exception {
		for (String[] invalid : List.of(new String[]{"Clearance", "DOUBLE", "0"}, new String[]{"AcceptRatio", "DOUBLE", "0"},
				new String[]{"TravelZ", "DOUBLE", "NaN"}, new String[]{"MaxPasses", "INT", "0"},
				new String[]{"StillTimeout", "DOUBLE", "0"}, new String[]{"TimeoutSec", "INT", "0"},
				new String[]{"VelocityScaling", "FLOAT", "1.5"}, new String[]{"ObjectName", "STRING", " peg"})) {
			Map<String, SubmodelElement> saved = new LinkedHashMap<>(inputs);
			input(invalid[0], DataTypeDefXsd.valueOf(invalid[1]), invalid[2]);
			assertThrows(invalid[0], IllegalArgumentException.class, this::invoke);
			inputs = saved;
		}
		inputs.put("Entry", collection("Entry", property("x", DataTypeDefXsd.DOUBLE, "NaN"),
				property("y", DataTypeDefXsd.DOUBLE, "0"), property("z", DataTypeDefXsd.DOUBLE, "0")));
		assertThrows(IllegalArgumentException.class, this::invoke);
		inputs.put("Entry", collection("Entry", property("x", DataTypeDefXsd.DOUBLE, "0.5"), property("y", DataTypeDefXsd.DOUBLE, "0")));
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
		assertEquals("old", value("StopReason"));
	}

	@Test
	public void missingRequiredOutputFailsBeforeCommunication() throws Exception {
		out.remove("Passes");
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
	}

	private MDTOperationProvider frameworkProvider() throws Exception {
		ServiceContext context = org.mockito.Mockito.mock(ServiceContext.class);
		var reference = new DefaultReference.Builder().type(ReferenceTypes.MODEL_REFERENCE)
				.keys(List.of(new DefaultKey.Builder().type(KeyTypes.SUBMODEL).value("test-data").build(),
						new DefaultKey.Builder().type(KeyTypes.OPERATION).value("AlignHeldObject").build())).build();
		org.mockito.Mockito.when(context.getOperationOutputVariables(reference)).thenReturn(variables(out));
		var config = new MDTOperationProviderConfig();
		config.setJava(server.config(AlignHeldObjectProvider.class));
		return new MDTOperationProvider(context, reference, config);
	}

	private static OperationVariable[] variables(Map<String, SubmodelElement> values) {
		return values.values().stream().map(v -> new DefaultOperationVariable.Builder().value(v).build())
				.toArray(OperationVariable[]::new);
	}

	private static String value(OperationVariable[] variables, String name) {
		return Arrays.stream(variables).map(OperationVariable::getValue).filter(v -> v.getIdShort().equals(name))
				.map(v -> ((Property)v).getValue()).findFirst().orElseThrow();
	}

	private synchronized void drifts(double... values) { m_drifts = values; }

	private synchronized Map<String,Object> sceneEnvelope() {
		double drift = m_drifts[Math.min(m_moves, m_drifts.length - 1)];
		double[] center = { m_tcp[0] + HELD[0] + drift, m_tcp[1] + HELD[1], m_tcp[2] + HELD[2] + 0.025 };
		Map<String,Object> peg = Map.of("type", "cylinder", "dimensions", List.of(0.05, 0.008),
				"pose", Map.of("position", position(center), "orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
		Map<String,Object> objects = new LinkedHashMap<>(Map.of("peg", peg));
		if ( m_invalidName ) {
			Map<String,Object> other = Map.of("type", "box", "dimensions", List.of(0.04, 0.04, 0.04),
					"pose", Map.of("position", Map.of("x", 0.7, "y", 0.2, "z", 0.02), "orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
			objects.put("another-box", other);
		}
		return Map.of("quality", "OK", "received_at", Instant.now().toString(),
				"value", Map.of("header", Map.of("frame_id", "panda_link0"), "objects", objects));
	}

	private synchronized Map<String,Object> eeValue() {
		Quaternion q = Geometry.downWithYaw(Math.toRadians(-90));
		return Map.of("header", Map.of("frame_id", "panda_link0"), "pose", Map.of("position", position(m_tcp),
				"orientation", Map.of("x", q.x(), "y", q.y(), "z", q.z(), "w", q.w())));
	}

	private static Map<String,Object> position(double[] p) { return Map.of("x", p[0], "y", p[1], "z", p[2]); }
	private void invoke() throws Exception { provider.invokeSync(inputs, Map.of(), out); }
	private String value(String name) { return ((Property)out.get(name)).getValue(); }
	private void input(String name, DataTypeDefXsd type, String value) { inputs.put(name, property(name, type, value)); }
}
