package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultReference;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultKey;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperationVariable;
import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

import mdt.assetconnection.operation.MDTOperationProvider;
import mdt.assetconnection.operation.MDTOperationProviderConfig;
import mdt.robot.client.operation.Grasp;
import mdt.robot.client.operation.GripperCheck;
import mdt.robot.client.operation.StepResult;
import mdt.robot.client.OperationTimeoutException;
import mdt.robot.client.TwinException;

public class GraspProviderTest {
	private TwinServer server;
	private GraspProvider provider;
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
		provider = new GraspProvider(null, null, server.config(GraspProvider.class));
		inputs = new LinkedHashMap<>();
		inputs.put("ApproachPose", pose("ApproachPose", 0.4));
		inputs.put("GraspPose", pose("GraspPose", 0.5));
		out = new LinkedHashMap<>();
		out.put("GoalsReached", property("GoalsReached", DataTypeDefXsd.BOOLEAN, "true"));
		out.put("GraspAttempted", property("GraspAttempted", DataTypeDefXsd.BOOLEAN, "true"));
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
			else { outputs.put("at_goal", stage != failAt); outputs.put("stalled", true); }
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
	public void executesFourStagesWithDefaultsAndNoLift() throws Exception {
		assertTrue(server.requests.isEmpty());
		invoke();
		assertEquals(4, commands.size());
		assertEquals("open", commands.get(0).path("target").asText());
		assertEquals(0.4, commands.get(1).path("pose").path("position").path("x").asDouble(), 1e-6);
		assertEquals(0.5, commands.get(2).path("pose").path("position").path("x").asDouble(), 1e-6);
		assertEquals("grasp", commands.get(3).path("target").asText());
		assertEquals("0.2", commands.get(1).path("velocity_scaling").asText());
		assertFalse(commands.get(1).has("frame"));
		assertEquals("true", value("GoalsReached"));
		assertEquals("true", value("GraspAttempted"));
		assertEquals("", value("FailedStage"));
		assertEquals("", value("FailureReason"));
	}

	@Test
	public void explicitOptionsAndAsyncMovementAreUsed() throws Exception {
		asyncMovement = true;
		input("ApproachVelocityScaling", DataTypeDefXsd.FLOAT, "0.3");
		input("DescentVelocityScaling", DataTypeDefXsd.FLOAT, "0.1");
		input("OpenTarget", DataTypeDefXsd.STRING, "wide_open");
		input("GraspTarget", DataTypeDefXsd.STRING, "hold");
		input("Tolerance", DataTypeDefXsd.DOUBLE, "0.01");
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "0");
		input("TimeoutSec", DataTypeDefXsd.INT, "5");
		invoke();
		assertEquals("wide_open", commands.get(0).path("target").asText());
		assertEquals("hold", commands.get(3).path("target").asText());
		assertEquals("0.3", commands.get(1).path("velocity_scaling").asText());
		assertEquals("0.1", commands.get(2).path("velocity_scaling").asText());
		assertEquals(2, server.requests.stream().filter(r -> r.contains("/sessions/test")).count());
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
			assertEquals(stage == 3 ? "true" : "false", value("GraspAttempted"));
			assertEquals(List.of("OPEN", "APPROACH", "DESCEND", "GRASP").get(stage), value("FailedStage"));
			assertFalse(value("FailureReason").isBlank());
		}
	}

	@Test
	public void remoteFailedAndCancelledReturnDiagnosticsNormally() throws Exception {
		for (String status : List.of("FAILED", "CANCELLED")) {
			commands.clear();
			failAt = 3;
			terminalStatus = status;
			invoke();
			assertFalse(value("FailureReason").isBlank());
			assertEquals("GRASP", value("FailedStage"));
			assertEquals("true", value("GraspAttempted"));
			assertEquals("false", value("GoalsReached"));
			assertEquals(4, commands.size());
		}
	}

	@Test
	public void rejectionPublishesProgressAndDoesNotRetryOrOpenAgain() throws Exception {
		rejectAt = 3;
		var failure = assertThrows(TwinException.class, this::invoke);
		assertEquals("RESOURCE_BUSY", failure.getCode());
		assertEquals("GRASP", value("FailedStage"));
		assertEquals("true", value("GraspAttempted"));
		assertEquals("false", value("GoalsReached"));
		assertEquals(4, commands.size());
	}

	@Test(timeout = 10000)
	public void asyncSessionTimeoutStopsAtThatStageWithoutCancellingRemote() throws Exception {
		// DESCEND(세 번째 명령)의 세션만 끝나지 않는다. TimeoutSec가 지나면 그 단계에서 멈춰야 한다.
		asyncMovement = true;
		input("TimeoutSec", DataTypeDefXsd.INT, "1");
		server.on("GET", "/operations/sessions/test",
				request -> commands.size() == 3 ? json(200, Map.of("status", "RUNNING")) : json(200, lastResult));
		assertThrows(OperationTimeoutException.class, this::invoke);
		assertEquals(3, commands.size());
		assertEquals("DESCEND", value("FailedStage"));
		assertTrue(value("FailureReason"), value("FailureReason").contains("OperationTimeoutException"));
		assertEquals("false", value("GraspAttempted"));
		assertEquals("false", value("GoalsReached"));
		assertTrue("remote session is not cancelled automatically",
					server.requests.stream().noneMatch(r -> r.startsWith("DELETE ")));
	}

	@Test
	public void invalidInputsDoNotOpenGripperOrChangeOutputs() throws Exception {
		for (String[] invalid : List.of(new String[]{"ApproachVelocityScaling", "float", "0"},
				new String[]{"DescentVelocityScaling", "float", "NaN"}, new String[]{"Tolerance", "double", "0"},
				new String[]{"SettleTimeout", "double", "-1"}, new String[]{"TimeoutSec", "int", "0"},
				new String[]{"GraspTarget", "string", " grasp"})) {
			input(invalid[0], DataTypeDefXsd.valueOf(invalid[1].toUpperCase(java.util.Locale.ROOT)), invalid[2]);
			assertThrows(IllegalArgumentException.class, this::invoke);
			inputs.remove(invalid[0]);
		}
		inputs.put("GraspPose", pose("GraspPose", Double.NaN));
		assertThrows(IllegalArgumentException.class, this::invoke);
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
	public void openLoopWaitsForSettlingBeforeContinuing() throws Exception {
		closedLoop = false;
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "2");
		invoke();
		assertEquals("true", value("GoalsReached"));
		assertTrue(server.requests.stream().anyMatch(r -> r.contains("/variables/ee_pose")));
		assertEquals(4, commands.size());
	}

	@Test
	public void settlingTimeoutStopsBeforeDescending() throws Exception {
		closedLoop = false;
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "0.05");
		invoke();
		assertEquals(2, commands.size());
		assertEquals("APPROACH", value("FailedStage"));
		assertEquals("false", value("GraspAttempted"));
	}

	@Test(timeout = 10000)
	public void interruptionDuringGraspKeepsAttemptedAndDoesNotAutoOpen() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("POST", "/operations/move_gripper_to_target", request -> {
			if (commands.isEmpty()) { return respond(request); }
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return json(200, Map.of("status", "COMPLETED", "outputs", Map.of("at_goal", true), "padding", "x".repeat(65536)));
		});
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try { invoke(); } catch (Throwable e) { failure.set(e); }
		});
		worker.start();
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			worker.interrupt(); release.countDown(); worker.join(3000);
			assertFalse(worker.isAlive());
			assertTrue(String.valueOf(failure.get()), failure.get() instanceof InterruptedException);
			assertEquals("false", value("GoalsReached"));
			assertEquals("true", value("GraspAttempted"));
			assertEquals("GRASP", value("FailedStage"));
			assertEquals(4, server.requests.stream().filter(r -> r.startsWith("POST ")).count());
		}
		finally { release.countDown(); worker.interrupt(); worker.join(3000); }
	}

	@Test
	public void frameworkReturnsDiagnosticsForEveryUnconfirmedStage() throws Exception {
		MDTOperationProvider framework = frameworkProvider();
		for (String status : List.of("COMPLETED", "FAILED", "CANCELLED")) {
			terminalStatus = status;
			for (int stage = 0; stage < 4; stage++) {
				commands.clear();
				failAt = stage;
				OperationVariable[] result = framework.invoke(variables(inputs), new OperationVariable[0]);
				assertEquals("false", value(result, "GoalsReached"));
				assertEquals(stage == 3 ? "true" : "false", value(result, "GraspAttempted"));
				assertEquals(Grasp.Stage.values()[stage].name(), value(result, "FailedStage"));
				assertFalse(value(result, "FailureReason").isBlank());
				assertEquals(stage + 1, commands.size());
				// 프레임워크가 복제한 결과를 반환했는지 확인한다. 원본 출력 바인딩은 그대로다.
				assertEquals("old", value("FailedStage"));
			}
		}
	}

	@Test
	public void frameworkAsyncUsesSuccessCallbackForUnconfirmedGrasp() throws Exception {
		failAt = 3;
		terminalStatus = "FAILED";
		CompletableFuture<OperationVariable[]> response = new CompletableFuture<>();
		frameworkProvider().invokeAsync(variables(inputs), new OperationVariable[0],
				(outputs, inoutputs) -> response.complete(outputs), response::completeExceptionally);
		OperationVariable[] result = response.get(5, TimeUnit.SECONDS);
		assertEquals("false", value(result, "GoalsReached"));
		assertEquals("true", value(result, "GraspAttempted"));
		assertEquals("GRASP", value(result, "FailedStage"));
		assertEquals("old", value("FailedStage"));
	}

	@Test
	public void frameworkStillFailsOnRequestRejectionAndDoesNotReturnDiagnostics() throws Exception {
		rejectAt = 3;
		MDTOperationProvider framework = frameworkProvider();
		assertThrows(AssetConnectionException.class,
				() -> framework.invoke(variables(inputs), new OperationVariable[0]));
		assertEquals("old", value("FailedStage"));
		assertEquals(4, commands.size());
	}

	@Test
	public void interruptionBetweenStepsDoesNotNameSuccessfulStepAsFailed() {
		assertNull(GraspProvider.describeStop(List.of(), null).failedStage());
		assertTrue(GraspProvider.describeStop(List.of(), null).context().contains("OPEN 진입 전"));
		for (Grasp.Stage stage : Grasp.Stage.values()) {
			var successfulStep = new StepResult<>(stage, null, new GripperCheck(true, false, null, null),
					Optional.empty(), null);
			var stopped = GraspProvider.describeStop(List.of(successfulStep), null);
			assertNull(stopped.failedStage());
			if (stage == Grasp.Stage.GRASP) {
				assertEquals("모든 단계 목표 확인 후 중단", stopped.context());
			}
			else {
				assertTrue(stopped.context().contains(stage + " 완료 후"));
				assertTrue(stopped.context().contains(Grasp.Stage.values()[stage.ordinal() + 1] + " 진입 전"));
			}
			var failedStep = new StepResult<>(stage, null, null, Optional.empty(), new InterruptedException());
			assertEquals(stage, GraspProvider.describeStop(List.of(failedStep), null).failedStage());
		}
	}

	@Test
	public void finePrecisionSettleTimeoutIsRoundedInsteadOfRejected() throws Exception {
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "0.0000000001");
		invoke();
		assertEquals("true", value("GoalsReached"));
	}

	private MDTOperationProvider frameworkProvider() throws Exception {
		ServiceContext context = org.mockito.Mockito.mock(ServiceContext.class);
		var reference = new DefaultReference.Builder().type(ReferenceTypes.MODEL_REFERENCE)
				.keys(List.of(new DefaultKey.Builder().type(KeyTypes.SUBMODEL).value("test-data").build(),
						new DefaultKey.Builder().type(KeyTypes.OPERATION).value("Grasp").build())).build();
		org.mockito.Mockito.when(context.getOperationOutputVariables(reference)).thenReturn(variables(out));
		var config = new MDTOperationProviderConfig();
		config.setJava(server.config(GraspProvider.class));
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
	private void input(String name, DataTypeDefXsd type, String value) { inputs.put(name, property(name, type, value)); }
}
