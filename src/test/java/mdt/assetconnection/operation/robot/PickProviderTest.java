package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

import mdt.assetconnection.operation.MDTOperationProvider;
import mdt.assetconnection.operation.MDTOperationProviderConfig;
import mdt.robot.client.OperationTimeoutException;
import mdt.robot.client.TwinException;
import mdt.robot.client.operation.GripperCheck;
import mdt.robot.client.operation.Pick;
import mdt.robot.client.operation.StepResult;

/**
 * 가짜 트윈의 단순한 물리 모형으로 Provider의 입출력을 검증한다.
 * <p>
 * 물체 중심은 처음에 파지 자세 + HELD에 있다. 파지 시 붙으면(m_attach) 이후 도구 기준점 + HELD + slip을 따라가고,
 * 붙지 않으면 그 자리에 남는다. slip은 들어 올린 뒤에만 더한다.
 */
public class PickProviderTest {
	private static final String BATCH = "/variables?names=ee_pose,scene_objects";
	private static final double[] HELD = { 0.001, -0.002, -0.03 };
	private static final double GRASP_Z = 0.1;

	private TwinServer server;
	private PickProvider provider;
	private Map<String, SubmodelElement> inputs;
	private Map<String, SubmodelElement> out;
	// 아래 모형 상태는 this로 보호한다.
	private double[] m_tcp = { 0.3, 0.0, 0.4 };
	private double[] m_object = { 0.4 + HELD[0], HELD[1], GRASP_Z + HELD[2] };
	private boolean m_attach = true;
	private boolean m_attached;
	private boolean m_lifted;
	private double m_slip;
	private int m_rejectAt = -1;
	private boolean m_invalidName;	// AAS idShort로 쓸 수 없는 이름의 물체를 장면에 더한다.
	private int m_hangAt = -1;		// 0 이상이면 이동을 비동기로 받고, 이 순번 명령의 세션은 끝나지 않는다.
	private Map<String,Object> m_lastMove;
	private final List<JsonNode> m_commands = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		server = new TwinServer("unused");
		server.on("GET", "/operations", request -> json(200, Map.of("operations", List.of(
				Map.of("name", "move_gripper_to_target", "kind", "sync"), Map.of("name", "move_linear", "kind", m_hangAt >= 0 ? "async" : "sync")))));
		server.on("GET", "/operations/sessions/test", request -> session());
		server.on("POST", "/operations/move_gripper_to_target", this::gripper);
		server.on("POST", "/operations/move_linear", this::move);
		server.on("GET", BATCH, request -> json(200, Map.of("values", Map.of(
				"ee_pose", Map.of("quality", "OK", "received_at", Instant.now().toString(), "value", eeValue()),
				"scene_objects", sceneEnvelope()))));
		server.start();
		provider = new PickProvider(null, null, server.config(PickProvider.class));
		inputs = new LinkedHashMap<>();
		inputs.put("ApproachPose", poseAt("ApproachPose", 0.3));
		inputs.put("GraspPose", poseAt("GraspPose", GRASP_Z));
		out = new LinkedHashMap<>();
		out.put("GoalsReached", property("GoalsReached", DataTypeDefXsd.BOOLEAN, "true"));
		out.put("GraspAttempted", property("GraspAttempted", DataTypeDefXsd.BOOLEAN, "true"));
		out.put("FailedStage", property("FailedStage", DataTypeDefXsd.STRING, "old"));
		out.put("FailureReason", property("FailureReason", DataTypeDefXsd.STRING, "old"));
		out.put("HoldDriftM", property("HoldDriftM", DataTypeDefXsd.DOUBLE, "99"));
		out.put("HeldOffset", collection("HeldOffset", property("x", DataTypeDefXsd.DOUBLE, "9")));
		out.put("Objects", collection("Objects", collection("oldObject")));
	}

	@After public void tearDown() throws Exception { server.close(); }

	@Test
	public void withoutObjectNameLiftsToApproachPoseWithoutObserving() throws Exception {
		invoke();
		assertEquals(5, m_commands.size());
		assertEquals("grasp", m_commands.get(3).path("target").asText());
		assertEquals(0.3, m_commands.get(4).path("pose").path("position").path("z").asDouble(), 1e-6);
		assertEquals("0.2", m_commands.get(4).path("velocity_scaling").asText());
		assertEquals("true", value("GoalsReached"));
		assertEquals("true", value("GraspAttempted"));
		assertEquals("", value("FailedStage"));
		assertEquals("", value("FailureReason"));
		assertEquals("99", value("HoldDriftM"));
		assertEquals("oldObject", objects().get(0).getIdShort());
		assertTrue(server.requests.stream().noneMatch(r -> r.contains("/variables")));
	}

	@Test
	public void explicitLiftPoseAndBlankObjectNameAreUsed() throws Exception {
		inputs.put("LiftPose", poseAt("LiftPose", 0.25));
		input("LiftVelocityScaling", DataTypeDefXsd.FLOAT, "0.15");
		input("ObjectName", DataTypeDefXsd.STRING, "");
		invoke();
		assertEquals(0.25, m_commands.get(4).path("pose").path("position").path("z").asDouble(), 1e-6);
		assertEquals("0.15", m_commands.get(4).path("velocity_scaling").asText());
		assertEquals("true", value("GoalsReached"));
		assertTrue(server.requests.stream().noneMatch(r -> r.contains("/variables")));
	}

	@Test(timeout = 10000)
	public void heldObjectPublishesHoldDiagnostics() throws Exception {
		input("ObjectName", DataTypeDefXsd.STRING, "box");
		invoke();
		assertEquals("true", value("GoalsReached"));
		assertEquals("", value("FailedStage"));
		assertEquals(0, Double.parseDouble(value("HoldDriftM")), 1e-9);
		assertEquals(HELD[0], component("x"), 1e-9);
		assertEquals(HELD[1], component("y"), 1e-9);
		assertEquals(HELD[2], component("z"), 1e-9);
		assertEquals(List.of("box"), objects().stream().map(SubmodelElement::getIdShort).toList());
		assertEquals(5, server.requests.stream().filter(r -> r.startsWith("POST ")).count());
	}

	@Test(timeout = 10000)
	public void pandaHandReferenceChangesOffsetButNotDrift() throws Exception {
		input("ObjectName", DataTypeDefXsd.STRING, "box");
		input("TcpReference", DataTypeDefXsd.STRING, "PANDA_HAND_TCP");
		invoke();
		assertEquals("true", value("GoalsReached"));
		assertEquals(0, Double.parseDouble(value("HoldDriftM")), 1e-9);
		assertTrue("hand TCP lies below the reported flange", component("z") > HELD[2] + 0.05);
	}

	@Test(timeout = 10000)
	public void objectLeftBehindReturnsMeasureHeldNormally() throws Exception {
		synchronized ( this ) { m_attach = false; }
		input("ObjectName", DataTypeDefXsd.STRING, "box");
		invoke();
		assertEquals("false", value("GoalsReached"));
		assertEquals("true", value("GraspAttempted"));
		assertEquals("MEASURE_HELD", value("FailedStage"));
		assertTrue(value("FailureReason"), value("FailureReason").startsWith("물체가 그리퍼와 함께 움직이지 않았다"));
		// 들어 올린 높이(0.3 - 0.1)만큼 벌어진다.
		assertEquals(0.2, Double.parseDouble(value("HoldDriftM")), 1e-6);	// 입력 pose가 xs:float이다.
		assertEquals(HELD[2] - 0.2, component("z"), 1e-6);
	}

	@Test(timeout = 10000)
	public void holdToleranceInputIsApplied() throws Exception {
		synchronized ( this ) { m_slip = 0.003; }
		input("ObjectName", DataTypeDefXsd.STRING, "box");
		input("HoldTolerance", DataTypeDefXsd.DOUBLE, "0.002");
		invoke();
		assertEquals("false", value("GoalsReached"));
		assertEquals("MEASURE_HELD", value("FailedStage"));
		assertEquals(0.003, Double.parseDouble(value("HoldDriftM")), 1e-9);
	}

	@Test
	public void rejectionDuringLiftPublishesProgressAndThrows() throws Exception {
		m_rejectAt = 4;
		input("ObjectName", DataTypeDefXsd.STRING, "box");
		var failure = assertThrows(TwinException.class, this::invoke);
		assertEquals("RESOURCE_BUSY", failure.getCode());
		assertEquals("LIFT", value("FailedStage"));
		assertEquals("true", value("GraspAttempted"));
		assertEquals("false", value("GoalsReached"));
		assertEquals("99", value("HoldDriftM"));
	}

	@Test(timeout = 10000)
	public void asyncSessionTimeoutDuringLiftStopsBeforeMeasuring() throws Exception {
		// LIFT(다섯 번째 명령)의 세션만 끝나지 않는다. 파지 시도 기록은 남고 두 번째 측정은 하지 않아야 한다.
		synchronized ( this ) { m_hangAt = 4; }
		input("ObjectName", DataTypeDefXsd.STRING, "box");
		input("TimeoutSec", DataTypeDefXsd.INT, "1");
		assertThrows(OperationTimeoutException.class, this::invoke);
		assertEquals(5, m_commands.size());
		assertEquals("LIFT", value("FailedStage"));
		assertTrue(value("FailureReason"), value("FailureReason").contains("OperationTimeoutException"));
		assertEquals("true", value("GraspAttempted"));
		assertEquals("false", value("GoalsReached"));
		assertEquals("99", value("HoldDriftM"));
		assertEquals(1, server.requests.stream().filter(r -> r.contains("/variables?")).count());
		assertTrue("remote session is not cancelled automatically",
					server.requests.stream().noneMatch(r -> r.startsWith("DELETE ")));
	}

	@Test
	public void invalidInputsDoNotOpenGripperOrChangeOutputs() throws Exception {
		for (String[] invalid : List.of(new String[]{"LiftVelocityScaling", "float", "0"},
				new String[]{"HoldTolerance", "double", "0"}, new String[]{"HoldTolerance", "double", "NaN"},
				new String[]{"TcpReference", "string", "FLANGE"}, new String[]{"ObjectName", "string", " box"})) {
			input(invalid[0], DataTypeDefXsd.valueOf(invalid[1].toUpperCase(java.util.Locale.ROOT)), invalid[2]);
			assertThrows(invalid[0], IllegalArgumentException.class, this::invoke);
			inputs.remove(invalid[0]);
		}
		inputs.put("LiftPose", poseAt("LiftPose", Double.NaN));
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
		assertEquals("old", value("FailedStage"));
	}

	@Test(timeout = 10000)
	public void unconvertibleSceneNameKeepsObjectsButReturnsPickResult() throws Exception {
		synchronized ( this ) { m_invalidName = true; }
		input("ObjectName", DataTypeDefXsd.STRING, "box");
		invoke();
		assertEquals("true", value("GoalsReached"));
		assertEquals("true", value("GraspAttempted"));
		assertEquals("", value("FailedStage"));
		assertEquals(0, Double.parseDouble(value("HoldDriftM")), 1e-9);
		assertEquals(HELD[2], component("z"), 1e-9);
		assertEquals("oldObject", objects().get(0).getIdShort());
	}

	@Test
	public void liftTooCloseToGraspIsRejectedBeforeMoving() throws Exception {
		input("ObjectName", DataTypeDefXsd.STRING, "box");
		inputs.put("LiftPose", poseAt("LiftPose", GRASP_Z + 0.005));
		var e = assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(e.getMessage(), e.getMessage().startsWith("liftPose must be at least 20.0 mm"));
		assertTrue(server.requests.isEmpty());
		assertEquals("old", value("FailedStage"));
	}

	@Test
	public void missingOutputBindingFailsBeforeCommunication() throws Exception {
		out.remove("GraspAttempted");
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
	}

	@Test
	public void interruptionAfterGraspNamesNextStageOfThisRun() {
		var grasped = new StepResult<>(Pick.Stage.GRASP, null, new GripperCheck(true, true, null, null), Optional.empty(), null);
		var steps = List.of(grasped);
		assertTrue(PickProvider.describeStop(steps, null, false).context().contains("GRASP 완료 후 LIFT 진입 전"));
		assertTrue(PickProvider.describeStop(steps, null, true).context().contains("GRASP 완료 후 MEASURE_GRIP 진입 전"));
		var lifted = new StepResult<>(Pick.Stage.LIFT, null, new GripperCheck(true, false, null, null), Optional.empty(), null);
		assertEquals("모든 단계 목표 확인 후 중단", PickProvider.describeStop(List.of(lifted), null, false).context());
		assertNull(PickProvider.describeStop(List.of(lifted), null, false).failedStage());
	}

	@Test(timeout = 10000)
	public void frameworkReturnsHoldFailureDiagnostics() throws Exception {
		synchronized ( this ) { m_attach = false; }
		input("ObjectName", DataTypeDefXsd.STRING, "box");
		OperationVariable[] result = frameworkProvider().invoke(variables(inputs), new OperationVariable[0]);
		assertEquals("false", value(result, "GoalsReached"));
		assertEquals("MEASURE_HELD", value(result, "FailedStage"));
		assertEquals(0.2, Double.parseDouble(value(result, "HoldDriftM")), 1e-6);
		// 프레임워크가 복제한 결과를 반환했는지 확인한다. 원본 출력 바인딩은 그대로다.
		assertEquals("old", value("FailedStage"));
	}

	private synchronized MockResponse gripper(RecordedRequest request) {
		JsonNode args = args(request);
		if ( m_commands.size() - 1 == m_rejectAt ) { return rejected(); }
		boolean grasp = "grasp".equals(args.path("target").asText());
		if ( grasp ) { m_attached = m_attach; }
		return json(200, Map.of("status", "COMPLETED", "outputs", Map.of("at_goal", true, "stalled", grasp)));
	}

	private synchronized MockResponse move(RecordedRequest request) {
		JsonNode args = args(request);
		if ( m_commands.size() - 1 == m_rejectAt ) { return rejected(); }
		JsonNode p = args.path("pose").path("position");
		m_tcp = new double[] { p.path("x").asDouble(), p.path("y").asDouble(), p.path("z").asDouble() };
		if ( m_attached ) { m_lifted = true; }
		Map<String,Object> result = Map.of("status", "COMPLETED", "outputs", Map.of("final_pose", Map.of("pose", args.path("pose")),
																					"closed_loop", true));
		if ( m_hangAt >= 0 ) {
			m_lastMove = result;
			return json(202, Map.of("status", "RUNNING", "session_endpoint", BASE + "/operations/sessions/test"));
		}
		return json(200, result);
	}

	private synchronized MockResponse session() {
		return (m_commands.size() - 1 == m_hangAt) ? json(200, Map.of("status", "RUNNING")) : json(200, m_lastMove);
	}

	private JsonNode args(RecordedRequest request) {
		try {
			JsonNode args = JSON.readTree(request.getBody().readUtf8()).path("inputs");
			m_commands.add(args);
			return args;
		}
		catch ( Exception e ) { throw new AssertionError(e); }
	}

	private static MockResponse rejected() {
		return json(409, Map.of("error", Map.of("code", "RESOURCE_BUSY", "message", "busy")));
	}

	private synchronized Map<String,Object> sceneEnvelope() {
		double[] center = m_object;
		if ( m_attached ) {
			double slip = m_lifted ? m_slip : 0;
			center = new double[] { m_tcp[0] + HELD[0] + slip, m_tcp[1] + HELD[1], m_tcp[2] + HELD[2] };
		}
		Map<String,Object> box = Map.of("type", "box", "dimensions", List.of(0.04, 0.04, 0.04),
				"pose", Map.of("position", position(center), "orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
		Map<String,Object> objects = new LinkedHashMap<>(Map.of("box", box));
		if ( m_invalidName ) {
			Map<String,Object> other = Map.of("type", "box", "dimensions", List.of(0.04, 0.04, 0.04),
					"pose", Map.of("position", Map.of("x", 0.7, "y", 0.2, "z", 0.02), "orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
			objects.put("another-box", other);
		}
		return Map.of("quality", "OK", "received_at", Instant.now().toString(),
				"value", Map.of("header", Map.of("frame_id", "panda_link0"), "objects", objects));
	}

	private synchronized Map<String,Object> eeValue() {
		return Map.of("header", Map.of("frame_id", "panda_link0"), "pose", Map.of("position", position(m_tcp),
				"orientation", Map.of("x", 1.0, "y", 0.0, "z", 0.0, "w", 0.0)));
	}

	private static Map<String,Object> position(double[] p) { return Map.of("x", p[0], "y", p[1], "z", p[2]); }

	/** 아래를 보는 자세(x축 180도 회전)의 x=0.4, y=0, z 위치. */
	private static SubmodelElementCollection poseAt(String name, double z) {
		return collection(name, collection("Position", property("x", DataTypeDefXsd.FLOAT, "0.4"),
				property("y", DataTypeDefXsd.FLOAT, "0"), property("z", DataTypeDefXsd.FLOAT, "" + z)),
				collection("Orientation", property("x", DataTypeDefXsd.FLOAT, "1"), property("y", DataTypeDefXsd.FLOAT, "0"),
						property("z", DataTypeDefXsd.FLOAT, "0"), property("w", DataTypeDefXsd.FLOAT, "0")));
	}

	private MDTOperationProvider frameworkProvider() throws Exception {
		ServiceContext context = org.mockito.Mockito.mock(ServiceContext.class);
		var reference = new DefaultReference.Builder().type(ReferenceTypes.MODEL_REFERENCE)
				.keys(List.of(new DefaultKey.Builder().type(KeyTypes.SUBMODEL).value("test-data").build(),
						new DefaultKey.Builder().type(KeyTypes.OPERATION).value("Pick").build())).build();
		org.mockito.Mockito.when(context.getOperationOutputVariables(reference)).thenReturn(variables(out));
		var config = new MDTOperationProviderConfig();
		config.setJava(server.config(PickProvider.class));
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

	private void invoke() throws Exception { provider.invokeSync(inputs, Map.of(), out); }
	private String value(String name) { return ((Property)out.get(name)).getValue(); }
	private List<SubmodelElement> objects() { return ((SubmodelElementCollection)out.get("Objects")).getValue(); }
	private double component(String axis) {
		return ((SubmodelElementCollection)out.get("HeldOffset")).getValue().stream().filter(e -> axis.equals(e.getIdShort()))
				.map(e -> Double.parseDouble(((Property)e).getValue())).findFirst().orElseThrow();
	}
	private void input(String name, DataTypeDefXsd type, String value) { inputs.put(name, property(name, type, value)); }
}
