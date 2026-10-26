package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultKey;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

import mdt.assetconnection.operation.MDTOperationProvider;
import mdt.assetconnection.operation.MDTOperationProviderConfig;
import mdt.robot.client.OperationTimeoutException;
import mdt.robot.client.TwinException;
import mdt.robot.client.operation.GripperCheck;
import mdt.robot.client.operation.Place;
import mdt.robot.client.operation.StepResult;

public class PlaceProviderTest {
	private static final List<String> STAGES = List.of("APPROACH", "DESCEND", "RELEASE", "RETREAT");

	private TwinServer server;
	private PlaceProvider provider;
	private Map<String, SubmodelElement> inputs;
	private Map<String, SubmodelElement> out;
	private final List<JsonNode> commands = new ArrayList<>();
	private int failAt = -1;
	private int rejectAt = -1;
	private String terminalStatus = "COMPLETED";
	private boolean closedLoop = true;
	private boolean asyncMovement;
	private volatile JsonNode lastPose;
	private volatile ObjectNode lastResult;

	@Before
	public void setUp() throws Exception {
		server = new TwinServer("unused");
		server.on("GET", "/operations", request -> json(200, Map.of("operations", List.of(
				Map.of("name", "move_gripper_to_target", "kind", "sync"),
				Map.of("name", "move_linear", "kind", asyncMovement ? "async" : "sync")))));
		server.on("POST", "/operations/move_gripper_to_target", this::respond);
		server.on("POST", "/operations/move_linear", this::respond);
		server.on("GET", "/operations/sessions/test", request -> json(200, lastResult));
		server.on("GET", "/variables/ee_pose", request -> json(200, Map.of("quality", "OK",
				"received_at", Instant.now().toString(), "value", Map.of("pose", lastPose))));
		server.start();
		provider = new PlaceProvider(null, null, server.config(PlaceProvider.class));
		inputs = new LinkedHashMap<>();
		inputs.put("ApproachPose", pose("ApproachPose", 0.4));
		inputs.put("PlacePose", pose("PlacePose", 0.5));
		out = new LinkedHashMap<>();
		out.put("GoalsReached", property("GoalsReached", DataTypeDefXsd.BOOLEAN, "true"));
		out.put("ReleaseAttempted", property("ReleaseAttempted", DataTypeDefXsd.BOOLEAN, "true"));
		out.put("FailedStage", property("FailedStage", DataTypeDefXsd.STRING, "old"));
		out.put("FailureReason", property("FailureReason", DataTypeDefXsd.STRING, "old"));
	}

	@After public void tearDown() throws Exception { server.close(); }

	private MockResponse respond(RecordedRequest request) {
		try {
			JsonNode args = JSON.readTree(request.getBody().readUtf8()).path("inputs");
			commands.add(args);
			int stage = commands.size() - 1;
			if (stage == rejectAt) { return json(409, Map.of("error", Map.of("code", "RESOURCE_BUSY", "message", "busy"))); }
			boolean movement = request.getPath().endsWith("/move_linear");
			ObjectNode outputs = JSON.createObjectNode();
			if (movement) {
				lastPose = args.path("pose");
				JsonNode finalPose = stage == failAt ? stamped(9).path("pose") : lastPose;
				outputs.set("final_pose", JSON.valueToTree(Map.of("pose", finalPose)));
				outputs.put("closed_loop", closedLoop);
			}
			else { outputs.put("at_goal", stage != failAt); outputs.put("stalled", false); }
			ObjectNode result = JSON.createObjectNode().put("status", stage == failAt ? terminalStatus : "COMPLETED");
			result.set("outputs", outputs);
			if (stage == failAt && !terminalStatus.equals("COMPLETED")) {
				result.set("error", JSON.valueToTree(Map.of("code", "FAILED_TEST", "message", "test failure")));
			}
			lastResult = result;
			return movement && asyncMovement
					? json(202, Map.of("status", "RUNNING", "session_endpoint", BASE + "/operations/sessions/test"))
					: json(200, result);
		}
		catch (Exception e) { throw new AssertionError(e); }
	}

	@Test
	public void executesFourStagesWithDefaultsAndRetreatsToApproachPose() throws Exception {
		invoke();
		assertEquals(4, commands.size());
		assertEquals(0.4, x(commands.get(0)), 1e-6);
		assertEquals(0.5, x(commands.get(1)), 1e-6);
		assertEquals("open", commands.get(2).path("target").asText());
		assertEquals(0.4, x(commands.get(3)), 1e-6);
		for (int movement : new int[]{0, 1, 3}) {
			assertEquals("0.2", commands.get(movement).path("velocity_scaling").asText());
			assertFalse(commands.get(movement).has("frame"));
		}
		assertEquals("true", value("GoalsReached"));
		assertEquals("true", value("ReleaseAttempted"));
		assertEquals("", value("FailedStage"));
		assertEquals("", value("FailureReason"));
	}

	@Test
	public void explicitRetreatPoseOptionsAndAsyncMovementAreUsed() throws Exception {
		asyncMovement = true;
		inputs.put("RetreatPose", pose("RetreatPose", 0.6));
		input("ApproachVelocityScaling", DataTypeDefXsd.FLOAT, "0.3");
		input("DescentVelocityScaling", DataTypeDefXsd.FLOAT, "0.1");
		input("RetreatVelocityScaling", DataTypeDefXsd.FLOAT, "0.25");
		input("ReleaseTarget", DataTypeDefXsd.STRING, "wide_open");
		input("Tolerance", DataTypeDefXsd.DOUBLE, "0.01");
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "0");
		input("TimeoutSec", DataTypeDefXsd.INT, "5");
		invoke();
		assertEquals(0.6, x(commands.get(3)), 1e-6);
		assertEquals("0.3", commands.get(0).path("velocity_scaling").asText());
		assertEquals("0.1", commands.get(1).path("velocity_scaling").asText());
		assertEquals("wide_open", commands.get(2).path("target").asText());
		assertEquals("0.25", commands.get(3).path("velocity_scaling").asText());
		assertEquals(3, server.requests.stream().filter(r -> r.contains("/sessions/test")).count());
		assertEquals("true", value("GoalsReached"));
	}

	@Test
	public void goalFailureStopsAtEachStageAndPublishesDiagnostics() throws Exception {
		for (int stage = 0; stage < 4; ++stage) {
			commands.clear();
			failAt = stage;
			invoke();
			assertEquals(stage + 1, commands.size());
			assertEquals("false", value("GoalsReached"));
			assertEquals(stage >= 2 ? "true" : "false", value("ReleaseAttempted"));
			assertEquals(STAGES.get(stage), value("FailedStage"));
			assertFalse(value("FailureReason").isBlank());
		}
	}

	@Test
	public void remoteFailedAndCancelledReturnDiagnosticsNormally() throws Exception {
		for (String status : List.of("FAILED", "CANCELLED")) {
			commands.clear();
			failAt = 2;
			terminalStatus = status;
			invoke();
			assertEquals(3, commands.size());
			assertEquals("false", value("GoalsReached"));
			assertEquals("true", value("ReleaseAttempted"));
			assertEquals("RELEASE", value("FailedStage"));
			assertFalse(value("FailureReason").isBlank());
		}
	}

	@Test
	public void rejectionPublishesProgressAndDoesNotRetreat() throws Exception {
		rejectAt = 2;
		var failure = assertThrows(TwinException.class, this::invoke);
		assertEquals("RESOURCE_BUSY", failure.getCode());
		assertEquals("RELEASE", value("FailedStage"));
		assertEquals("true", value("ReleaseAttempted"));
		assertEquals("false", value("GoalsReached"));
		assertEquals(3, commands.size());
	}

	@Test(timeout = 10000)
	public void asyncSessionTimeoutDuringRetreatKeepsReleaseRecord() throws Exception {
		// RETREAT(네 번째 명령)의 세션만 끝나지 않는다. 개방 기록은 남고 그 단계에서 멈춰야 한다.
		asyncMovement = true;
		input("TimeoutSec", DataTypeDefXsd.INT, "1");
		server.on("GET", "/operations/sessions/test",
				request -> commands.size() == 4 ? json(200, Map.of("status", "RUNNING")) : json(200, lastResult));
		assertThrows(OperationTimeoutException.class, this::invoke);
		assertEquals(4, commands.size());
		assertEquals("RETREAT", value("FailedStage"));
		assertTrue(value("FailureReason"), value("FailureReason").contains("OperationTimeoutException"));
		assertEquals("true", value("ReleaseAttempted"));
		assertEquals("false", value("GoalsReached"));
		assertTrue("remote session is not cancelled automatically",
					server.requests.stream().noneMatch(r -> r.startsWith("DELETE ")));
	}

	@Test
	public void invalidInputsDoNotMoveOrChangeOutputs() throws Exception {
		for (String[] invalid : List.of(new String[]{"ApproachVelocityScaling", "float", "0"},
				new String[]{"RetreatVelocityScaling", "float", "1.5"}, new String[]{"Tolerance", "double", "0"},
				new String[]{"SettleTimeout", "double", "-1"}, new String[]{"TimeoutSec", "int", "0"},
				new String[]{"ReleaseTarget", "string", " open"})) {
			input(invalid[0], DataTypeDefXsd.valueOf(invalid[1].toUpperCase(Locale.ROOT)), invalid[2]);
			assertThrows(invalid[0], IllegalArgumentException.class, this::invoke);
			inputs.remove(invalid[0]);
		}
		inputs.put("RetreatPose", pose("RetreatPose", Double.NaN));
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
		assertEquals("old", value("FailedStage"));
	}

	@Test
	public void missingOutputBindingFailsBeforeCommunication() throws Exception {
		out.remove("ReleaseAttempted");
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
	}

	@Test
	public void settlingTimeoutStopsBeforeRelease() throws Exception {
		closedLoop = false;
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "0.05");
		invoke();
		assertEquals(1, commands.size());
		assertEquals("APPROACH", value("FailedStage"));
		assertEquals("false", value("ReleaseAttempted"));
	}

	@Test
	public void openLoopWaitsForSettlingAndCompletesAllStages() throws Exception {
		closedLoop = false;
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "2");
		invoke();
		assertEquals(4, commands.size());
		assertEquals("open", commands.get(2).path("target").asText());
		assertTrue(server.requests.stream().anyMatch(r -> r.contains("/variables/ee_pose")));
		assertEquals("true", value("GoalsReached"));
		assertEquals("true", value("ReleaseAttempted"));
		assertEquals("", value("FailedStage"));
	}

	@Test(timeout = 10000)
	public void descentSettlingFailureDoesNotRelease() throws Exception {
		closedLoop = false;
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "2");
		// 접근 뒤에는 정착하지만, 하강 명령 뒤에는 관측이 STALE이라 정착을 확인할 수 없다.
		server.on("GET", "/variables/ee_pose", request -> json(200, Map.of(
				"quality", commands.size() >= 2 ? "STALE" : "OK",
				"received_at", Instant.now().toString(), "value", Map.of("pose", lastPose))));
		invoke();
		assertEquals(2, commands.size());
		assertEquals(0, server.requests.stream().filter(r -> r.endsWith("/move_gripper_to_target")).count());
		assertEquals("false", value("GoalsReached"));
		assertEquals("false", value("ReleaseAttempted"));
		assertEquals("DESCEND", value("FailedStage"));
		assertFalse(value("FailureReason").isBlank());
	}

	@Test(timeout = 10000)
	public void interruptionDuringReleaseIsPropagatedWithoutRetreat() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("POST", "/operations/move_gripper_to_target", request -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			// 인터럽트 뒤에도 소켓 읽기가 한 번 더 일어나도록 응답을 크게 만든다.
			return json(200, Map.of("status", "COMPLETED", "outputs", Map.of("at_goal", true), "padding", "x".repeat(65536)));
		});
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try { invoke(); } catch (Throwable e) { failure.set(e); }
		}, "place-release-interruption");
		worker.start();
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			worker.interrupt(); release.countDown(); worker.join(3000);
			assertFalse(worker.isAlive());
			assertTrue(String.valueOf(failure.get()), failure.get() instanceof InterruptedException);
			// 접근·하강만 요청하고 후퇴 이동은 요청하지 않는다.
			assertEquals(2, server.requests.stream().filter(r -> r.equals("POST " + BASE + "/operations/move_linear")).count());
			assertEquals(1, server.requests.stream().filter(r -> r.equals("POST " + BASE + "/operations/move_gripper_to_target")).count());
			assertEquals("false", value("GoalsReached"));
			assertEquals("true", value("ReleaseAttempted"));
			assertEquals("RELEASE", value("FailedStage"));
			assertTrue(value("FailureReason").contains("RELEASE"));
		}
		finally { release.countDown(); worker.interrupt(); worker.join(3000); }
	}

	@Test
	public void frameworkReturnsDiagnosticsForUnconfirmedStage() throws Exception {
		MDTOperationProvider framework = frameworkProvider();
		terminalStatus = "FAILED";
		failAt = 3;
		OperationVariable[] result = framework.invoke(variables(inputs), new OperationVariable[0]);
		assertEquals("false", value(result, "GoalsReached"));
		assertEquals("true", value(result, "ReleaseAttempted"));
		assertEquals("RETREAT", value(result, "FailedStage"));
		assertFalse(value(result, "FailureReason").isBlank());
		// 프레임워크가 복제한 결과를 반환했는지 확인한다. 원본 출력 바인딩은 그대로다.
		assertEquals("old", value("FailedStage"));
	}

	@Test
	public void frameworkStillFailsOnRequestRejection() throws Exception {
		rejectAt = 1;
		MDTOperationProvider framework = frameworkProvider();
		assertThrows(AssetConnectionException.class,
				() -> framework.invoke(variables(inputs), new OperationVariable[0]));
		assertEquals(2, commands.size());
	}

	@Test
	public void interruptionBetweenStepsDoesNotNameSuccessfulStepAsFailed() {
		assertNull(PlaceProvider.describeStop(List.of(), null).failedStage());
		assertTrue(PlaceProvider.describeStop(List.of(), null).context().contains("APPROACH 진입 전"));
		var released = new StepResult<>(Place.Stage.RELEASE, null, new GripperCheck(true, false, null, null),
				Optional.empty(), null);
		var stopped = PlaceProvider.describeStop(List.of(released), null);
		assertNull(stopped.failedStage());
		assertEquals("RELEASE 완료 후 RETREAT 진입 전 중단", stopped.context());
		var failed = new StepResult<>(Place.Stage.DESCEND, null, null, Optional.empty(), new InterruptedException());
		assertEquals(Place.Stage.DESCEND, PlaceProvider.describeStop(List.of(failed), null).failedStage());
	}

	private MDTOperationProvider frameworkProvider() throws Exception {
		ServiceContext context = org.mockito.Mockito.mock(ServiceContext.class);
		var reference = new DefaultReference.Builder().type(ReferenceTypes.MODEL_REFERENCE)
				.keys(List.of(new DefaultKey.Builder().type(KeyTypes.SUBMODEL).value("test-data").build(),
						new DefaultKey.Builder().type(KeyTypes.OPERATION).value("Place").build())).build();
		org.mockito.Mockito.when(context.getOperationOutputVariables(reference)).thenReturn(variables(out));
		var config = new MDTOperationProviderConfig();
		config.setJava(server.config(PlaceProvider.class));
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

	private static double x(JsonNode command) { return command.path("pose").path("position").path("x").asDouble(); }
	private void invoke() throws Exception { provider.invokeSync(inputs, Map.of(), out); }
	private String value(String name) { return ((Property)out.get(name)).getValue(); }
	private void input(String name, DataTypeDefXsd type, String value) { inputs.put(name, property(name, type, value)); }
}
