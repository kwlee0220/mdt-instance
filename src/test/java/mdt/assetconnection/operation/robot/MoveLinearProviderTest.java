package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.JsonNode;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;

import utils.RuntimeInterruptedException;

import mdt.robot.client.OperationFailedException;
import mdt.robot.client.TwinException;
import mdt.rt.Ros2SerDes;
import mdt.task.TaskException;

public class MoveLinearProviderTest {
	private Logger providerLogger;
	private Level previousLevel;
	private ListAppender<ILoggingEvent> logs;
	private TwinServer server;
	private MoveLinearProvider provider;
	private String observationQuality = "OK";
	private String jointQuality = "OK";
	private String jointFailure;
	private CountDownLatch jointEntered;
	private CountDownLatch releaseJoint;
	private Map<String, SubmodelElement> inputs;
	private Map<String, SubmodelElement> out;

	@Before
	public void setUp() throws Exception {
		providerLogger = (Logger)LoggerFactory.getLogger(MoveLinearProvider.class);
		previousLevel = providerLogger.getLevel();
		providerLogger.setLevel(Level.WARN);
		logs = new ListAppender<>();
		logs.start();
		providerLogger.addAppender(logs);
		server = new TwinServer("move_linear");
		server.on("GET", "/variables/ee_pose", request -> json(200, Map.of("quality", observationQuality,
				"received_at", Instant.now().toString(), "value", stamped(0.401))));
		server.on("GET", "/variables/joint_states", request -> {
			if (jointEntered != null) {
				jointEntered.countDown();
				if (!releaseJoint.await(5, TimeUnit.SECONDS)) { return new MockResponse().setResponseCode(500); }
				// Force another socket read after interruption while waiting for the response.
				return json(200, Map.of("quality", "OK", "value", Map.of("position", Map.of("panda_joint1", 0.9)),
						"padding", "x".repeat(65536)));
			}
			if ("http".equals(jointFailure)) {
				return json(503, Map.of("error", Map.of("code", "UNAVAILABLE", "message", "joint observation unavailable")));
			}
			if ("disconnect".equals(jointFailure)) { return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST); }
			if ("missing".equals(jointFailure)) { return json(200, Map.of("quality", "OK")); }
			return json(200, Map.of("quality", jointQuality, "value", Map.of("position", Map.of("panda_joint1", 0.9))));
		});
		server.start();
		var config = server.config(MoveLinearProvider.class);
		provider = new MoveLinearProvider(null, null, config);
		inputs = new LinkedHashMap<>();
		inputs.put("TargetPose", pose("TargetPose", 0.4));
		out = new LinkedHashMap<>();
		out.put("FinalPose", collection("FinalPose", collection("Header",
				property("Stamp", DataTypeDefXsd.LONG, "0"), property("FrameId", DataTypeDefXsd.STRING, "old")), pose("Pose", -1)));
		out.put("FinalJoints", collection("FinalJoints", property("panda_joint1", DataTypeDefXsd.FLOAT, "-1")));
		out.put("Reached", property("Reached", DataTypeDefXsd.BOOLEAN, "false"));
		out.put("PositionError", property("PositionError", DataTypeDefXsd.DOUBLE, "99"));
	}

	@After public void tearDown() throws Exception {
		try { server.close(); }
		finally {
			providerLogger.detachAppender(logs);
			providerLogger.setLevel(previousLevel);
			logs.stop();
		}
	}

	@Test
	public void defaultInputsPreserveServerDefaultsAndClosedLoopSkipsObservation() throws Exception {
		invoke();
		assertEquals(0.4, server.posted.path("pose").path("position").path("x").asDouble(), 1e-7);
		for (String key : List.of("velocity_scaling", "frame", "max_step", "jump_threshold", "max_duration_sec")) {
			assertFalse(key, server.posted.has(key));
		}
		assertEquals("true", value("Reached"));
		assertEquals(0.0, Double.parseDouble(value("PositionError")), 1e-7);
		assertEquals(0.4, finalX(), 1e-7);
		assertFalse(server.requests.stream().anyMatch(r -> r.contains("/variables/")));
	}

	@Test
	public void asyncCatalogAndAllMotionInputsAreUsed() throws Exception {
		server.async = true;
		input("VelocityScaling", DataTypeDefXsd.FLOAT, "0.25");
		input("MaxDurationSec", DataTypeDefXsd.INT, "120");
		input("Tolerance", DataTypeDefXsd.DOUBLE, "0.01");
		invoke();
		assertEquals(0.25, server.posted.path("velocity_scaling").asDouble(), 1e-7);
		assertFalse(server.posted.has("max_step"));
		assertFalse(server.posted.has("jump_threshold"));
		assertFalse(server.posted.has("frame"));
		assertEquals(120, server.posted.path("max_duration_sec").asInt());
		assertTrue(server.requests.contains("GET " + BASE + "/operations/sessions/test"));
		assertEquals(1, server.requests.stream().filter(r -> r.equals("POST " + BASE + "/operations/move_linear")).count());
		assertEquals("true", value("Reached"));
	}

	@Test
	public void requireReachedThrowsOnlyAfterOutputsAreUpdated() throws Exception {
		server.outputs.set("final_pose", stamped(0.45));
		input("RequireReached", DataTypeDefXsd.BOOLEAN, "true");
		assertThrows(TaskException.class, this::invoke);
		assertEquals("false", value("Reached"));
		assertEquals(0.45, finalX(), 1e-7);
		assertEquals(0.05, Double.parseDouble(value("PositionError")), 1e-7);
		assertEquals(0.2, finalJoint(), 1e-7);
	}

	@Test
	public void missingOrNullFinalStateDoesNotOverwriteExistingOutputs() throws Exception {
		server.outputs.remove("final_pose");
		server.outputs.putNull("final_joints");
		invoke();
		assertEquals("false", value("Reached"));
		assertEquals("99", value("PositionError"));
		assertEquals(-1, finalX(), 0);
		assertEquals(-1, finalJoint(), 0);
	}

	@Test
	public void retiredInputsAreRejectedEvenWhenEmptyOrPlacedInInoutput() throws Exception {
		for (String name : List.of("Frame", "MaxStep", "JumpThreshold")) {
			for (String value : new String[]{"grasp_center", "0.01", "", " ", null}) {
				input(name, DataTypeDefXsd.STRING, value);
				IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, this::invoke);
				assertTrue(failure.getMessage().contains(name));
				assertEquals("false", value("Reached"));
				assertEquals(-1, finalX(), 0);
				inputs.remove(name);
			}
			assertThrows(IllegalArgumentException.class, () -> provider.invokeSync(inputs,
					Map.of(name, property(name, DataTypeDefXsd.STRING, "")), out));
		}
		assertTrue(server.requests.isEmpty());
	}

	@Test
	public void remoteFailureAndCancellationStillThrow() throws Exception {
		for (String outcome : List.of("FAILED", "CANCELLED")) {
			logs.list.clear();
			server.status = outcome;
			OperationFailedException failure = assertThrows(OperationFailedException.class, this::invoke);
			assertEquals(outcome, failure.getResult().status());
			assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("status=" + outcome)
					&& e.getFormattedMessage().contains("twin=panda01")
					&& e.getFormattedMessage().contains("operation=move_linear")
					&& (!outcome.equals("FAILED") || (e.getFormattedMessage().contains("code=PLANNING_FAILED")
							&& e.getFormattedMessage().contains("계획 실패")))));
			assertEquals(-1, finalX(), 0);
		}
	}

	@Test
	public void rejectedRequestIsNotHiddenByExecutionException() throws Exception {
		server.rejection = 409;
		TwinException failure = assertThrows(TwinException.class, this::invoke);
		assertEquals("RESOURCE_BUSY", failure.getCode());
	}

	@Test
	public void staleObservationNeverTurnsSuccessfulSnapshotIntoReached() throws Exception {
		server.outputs.put("closed_loop", false);
		observationQuality = "STALE";
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "0.1");
		input("RequireReached", DataTypeDefXsd.BOOLEAN, "true");
		TaskException failure = assertThrows(TaskException.class, this::invoke);
		assertTrue(failure.getMessage().contains("정착"));
		assertEquals("false", value("Reached"));
		assertEquals("99", value("PositionError"));
		assertEquals(0.4, finalX(), 1e-7); // 진단용 원격 출력은 남지만 도달 판정에는 쓰지 않는다.
		assertFalse(server.requests.contains("GET " + BASE + "/variables/joint_states"));
	}

	@Test
	public void settledPoseAndGoodJointObservationUpdateOutputs() throws Exception {
		server.outputs.remove("closed_loop");
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "3");
		invoke();
		assertEquals("true", value("Reached"));
		assertEquals(0.401, finalX(), 1e-7);
		assertEquals(0.001, Double.parseDouble(value("PositionError")), 1e-7);
		assertEquals(0.9, finalJoint(), 1e-7);
	}

	@Test
	public void staleJointObservationDoesNotReplaceRemoteOutput() throws Exception {
		server.outputs.put("closed_loop", false);
		jointQuality = "STALE";
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "3");
		invoke();
		assertEquals(0.401, finalX(), 1e-7);
		assertEquals(0.2, finalJoint(), 1e-7);
	}

	@Test
	public void zeroSettleTimeoutKeepsSnapshotMode() throws Exception {
		server.outputs.put("closed_loop", false);
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "0");
		invoke();
		assertEquals("true", value("Reached"));
		assertFalse(server.requests.stream().anyMatch(r -> r.contains("/variables/")));
	}

	@Test
	public void invalidInputsAreRejectedBeforeMoving() throws Exception {
		for (String[] spec : List.of(new String[]{"MaxStep", "FLOAT", "0"},
				new String[]{"JumpThreshold", "FLOAT", "-1"}, new String[]{"MaxDurationSec", "INT", "0"},
				new String[]{"VelocityScaling", "FLOAT", "NaN"}, new String[]{"Tolerance", "DOUBLE", "Infinity"},
				new String[]{"SettleTimeout", "DOUBLE", "NaN"})) {
			input(spec[0], DataTypeDefXsd.valueOf(spec[1]), spec[2]);
			assertThrows(spec[0], IllegalArgumentException.class, this::invoke);
			inputs.remove(spec[0]);
		}
		assertTrue(server.requests.isEmpty());
	}

	@Test
	public void decimalSpeedRetainsFloatTextRepresentation() throws Exception {
		input("VelocityScaling", DataTypeDefXsd.FLOAT, "0.3");
		invoke();
		assertEquals("0.3", server.posted.path("velocity_scaling").asText());
	}

	@Test
	public void auxiliaryJointFailuresPreserveSettledOutputsAndRemoteJoints() throws Exception {
		server.outputs.put("closed_loop", false);
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "3");
		input("RequireReached", DataTypeDefXsd.BOOLEAN, "true");
		for (String failure : List.of("http", "disconnect", "missing")) {
			jointFailure = failure;
			((Property)out.get("Reached")).setValue("false");
			((Property)out.get("PositionError")).setValue("99");
			invoke();
			assertEquals("true", value("Reached"));
			assertEquals(0.001, Double.parseDouble(value("PositionError")), 1e-7);
			assertEquals(0.401, finalX(), 1e-7);
			assertEquals(0.2, finalJoint(), 1e-7);
		}
	}

	@Test
	public void jointFailureWithoutRemoteSnapshotLeavesPreviousJoints() throws Exception {
		server.outputs.put("closed_loop", false);
		server.outputs.remove("final_joints");
		jointFailure = "http";
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "3");
		invoke();
		assertEquals("true", value("Reached"));
		assertEquals(0.401, finalX(), 1e-7);
		assertEquals(-1, finalJoint(), 0);
	}

	@Test(timeout = 10000)
	public void interruptionDuringJointReadIsPropagatedNotTreatedAsFallback() throws Exception {
		server.outputs.put("closed_loop", false);
		input("SettleTimeout", DataTypeDefXsd.DOUBLE, "3");
		jointEntered = new CountDownLatch(1);
		releaseJoint = new CountDownLatch(1);
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread worker = new Thread(() -> {
			try { invoke(); }
			catch (Throwable e) { thrown.set(e); }
			finally { interrupted.set(Thread.currentThread().isInterrupted()); }
		}, "provider-joint-interruption");
		worker.start();
		try {
			assertTrue(jointEntered.await(5, TimeUnit.SECONDS));
			worker.interrupt();
			releaseJoint.countDown();
			worker.join(3000);
			assertFalse(worker.isAlive());
			assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof RuntimeInterruptedException);
			assertTrue(interrupted.get());
			assertEquals("false", value("Reached"));
			assertEquals("99", value("PositionError"));
			assertEquals(-1, finalX(), 0);
		}
		finally {
			releaseJoint.countDown();
			worker.interrupt();
			worker.join(3000);
		}
	}

	@Test
	public void nonFinitePoseComponentsAreRejectedBeforeAnyRequest() throws Exception {
		for (String part : List.of("Position", "Orientation")) {
			for (String axis : part.equals("Position") ? List.of("x", "y", "z") : List.of("x", "y", "z", "w")) {
				for (String invalid : List.of("NaN", "Infinity", "-Infinity")) {
					var target = pose("TargetPose", 0.4);
					var component = (org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection)target.getValue()
							.stream().filter(v -> v.getIdShort().equals(part)).findFirst().orElseThrow();
					((Property)component.getValue().stream().filter(v -> v.getIdShort().equals(axis))
							.findFirst().orElseThrow()).setValue(invalid);
					inputs.put("TargetPose", target);
					assertThrows(IllegalArgumentException.class, this::invoke);
					assertTrue(server.requests.isEmpty());
					assertEquals(-1, finalX(), 0);
				}
			}
		}
	}

	@Test
	public void settleDurationRoundsMillisecondsAndRejectsOverflow() throws Exception {
		assertEquals(java.time.Duration.ZERO, RobotTwinOperationProvider.settleDuration(0.0000000001));
		assertEquals(java.time.Duration.ofMillis(1), RobotTwinOperationProvider.settleDuration(0.0005));
		assertEquals(java.time.Duration.ofMillis(1235), RobotTwinOperationProvider.settleDuration(1.2345));
		for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
			input("SettleTimeout", DataTypeDefXsd.DOUBLE, Double.toString(invalid));
			assertThrows(IllegalArgumentException.class, this::invoke);
			assertTrue(server.requests.isEmpty());
		}
	}

	private void invoke() throws Exception { provider.invokeSync(inputs, Map.of(), out); }
	private void input(String name, DataTypeDefXsd type, String value) { inputs.put(name, property(name, type, value)); }
	private String value(String name) { return ((Property)out.get(name)).getValue(); }
	private double finalX() throws Exception {
		return Ros2SerDes.POSE_STAMPED_SERDE.toJsonNode(out.get("FinalPose")).path("pose").path("position").path("x").asDouble();
	}
	private double finalJoint() throws Exception {
		return Ros2SerDes.JOINT_STATES_SERDE.toJsonNode(out.get("FinalJoints")).path("position").path("panda_joint1").asDouble();
	}
}
