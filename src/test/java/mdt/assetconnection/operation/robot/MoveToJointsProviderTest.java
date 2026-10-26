package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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


import mdt.robot.client.OperationFailedException;
import mdt.robot.client.TwinException;
import mdt.rt.Ros2SerDes;

public class MoveToJointsProviderTest {
	private Logger providerLogger;
	private Level previousLevel;
	private ListAppender<ILoggingEvent> logs;
	private TwinServer server;
	private MoveToJointsProvider provider;
	private Map<String, SubmodelElement> inputs;
	private Map<String, SubmodelElement> out;

	@Before
	public void setUp() throws Exception {
		providerLogger = (Logger)LoggerFactory.getLogger(MoveToJointsProvider.class);
		previousLevel = providerLogger.getLevel();
		providerLogger.setLevel(Level.INFO);
		logs = new ListAppender<>();
		logs.start();
		providerLogger.addAppender(logs);
		server = new TwinServer("move_to_joints");
		server.start();
		var config = server.config(MoveToJointsProvider.class);
		provider = new MoveToJointsProvider(null, null, config);
		inputs = new LinkedHashMap<>();
		inputs.put("Joints", collection("Joints", property("panda_joint1", DataTypeDefXsd.FLOAT, "0.4")));
		out = new LinkedHashMap<>();
		out.put("FinalPose", collection("FinalPose", collection("Header",
				property("Stamp", DataTypeDefXsd.LONG, "0"), property("FrameId", DataTypeDefXsd.STRING, "old")), pose("Pose", -1)));
		out.put("FinalJoints", collection("FinalJoints", property("panda_joint1", DataTypeDefXsd.FLOAT, "-1")));
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
	public void omittedSpeedAndLimitUseServerDefaultsAndMapFinalState() throws Exception {
		invoke();
		assertEquals(0.4, server.posted.path("joints").path("panda_joint1").asDouble(), 1e-7);
		assertEquals(1, server.posted.path("joints").size());
		assertFalse(server.posted.has("velocity_scaling"));
		assertFalse(server.posted.has("max_duration_sec"));
		assertEquals(0.4, finalX(), 1e-7);
		assertEquals(0.2, finalJoint(), 1e-7);
		assertTrue(logs.list.isEmpty());
		assertFalse(server.requests.stream().anyMatch(r -> r.contains("/variables/")));
	}

	@Test
	public void asyncSessionReceivesExplicitSpeedAndServerLimit() throws Exception {
		server.async = true;
		input("VelocityScaling", DataTypeDefXsd.FLOAT, "0.25");
		input("MaxDurationSec", DataTypeDefXsd.INT, "120");
		invoke();
		assertEquals(0.25, server.posted.path("velocity_scaling").asDouble(), 1e-7);
		assertEquals(120, server.posted.path("max_duration_sec").asInt());
		assertTrue(server.requests.contains("GET " + BASE + "/operations/sessions/test"));
		assertEquals(1, server.requests.stream().filter(r -> r.equals("POST " + BASE + "/operations/move_to_joints")).count());
		assertEquals(0.2, finalJoint(), 1e-7);
	}

	@Test
	public void unconfirmedArrivalDoesNotFailCompletedOperation() throws Exception {
		for (JsonNode closedLoop : List.of(JSON.getNodeFactory().booleanNode(false),
				JSON.getNodeFactory().nullNode(), JSON.getNodeFactory().textNode("true"),
				JSON.getNodeFactory().textNode("false"), JSON.getNodeFactory().numberNode(0))) {
			logs.list.clear();
			server.outputs.set("closed_loop", closedLoop);
			invoke();
			assertEquals(0.4, finalX(), 1e-7);
			String reason = closedLoop.isBoolean() ? "open loop" : closedLoop.isNull() ? "정보 없음" : "형식 오류";
			Level expectedLevel = closedLoop.isBoolean() ? Level.INFO : Level.WARN;
			assertEquals(1, logs.list.size());
			assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == expectedLevel
					&& e.getFormattedMessage().contains("arrival could not be confirmed")
					&& e.getFormattedMessage().contains("twin=panda01")
					&& e.getFormattedMessage().contains("operation=move_to_joints")
					&& e.getFormattedMessage().contains(reason)));
		}
		logs.list.clear();
		server.outputs.remove("closed_loop");
		invoke();
		assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("정보 없음")));
		assertFalse(server.requests.stream().anyMatch(r -> r.contains("/variables/")));
	}

	@Test
	public void missingOrNullFinalOutputsPreservePreviousValues() throws Exception {
		server.outputs.remove("final_pose");
		server.outputs.putNull("final_joints");
		invoke();
		assertEquals(-1, finalX(), 0);
		assertEquals(-1, finalJoint(), 0);
	}

	@Test
	public void remoteFailureAndCancellationThrowBeforeUpdatingOutputs() throws Exception {
		for (boolean session : new boolean[]{false, true}) {
			server.async = session;
			// New provider has a fresh operation catalog cache.
			var config = server.config(MoveToJointsProvider.class);
			provider = new MoveToJointsProvider(null, null, config);
			for (String outcome : List.of("FAILED", "CANCELLED")) {
				logs.list.clear();
				server.status = outcome;
				OperationFailedException failure = assertThrows(OperationFailedException.class, this::invoke);
				assertEquals(outcome, failure.getResult().status());
				assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("status=" + outcome)
						&& e.getFormattedMessage().contains("twin=panda01")
					&& e.getFormattedMessage().contains("operation=move_to_joints")
						&& (!outcome.equals("FAILED") || (e.getFormattedMessage().contains("code=PLANNING_FAILED")
								&& e.getFormattedMessage().contains("손가락 관절")))));
				assertEquals(-1, finalX(), 0);
				assertEquals(-1, finalJoint(), 0);
			}
		}
	}

	@Test
	public void rejectionExposesOriginalException() throws Exception {
		for (int code : new int[]{400, 409}) {
			server.rejection = code;
			TwinException failure = assertThrows(TwinException.class, this::invoke);
			assertEquals(code, failure.getHttpStatus());
			assertEquals(-1, finalX(), 0);
		}
	}

	@Test
	public void invalidInputsFailBeforeCommunication() throws Exception {
		for (String speed : List.of("0", "-0.1", "1.1", "NaN", "Infinity")) {
			input("VelocityScaling", DataTypeDefXsd.FLOAT, speed);
			assertThrows(IllegalArgumentException.class, this::invoke);
		}
		inputs.remove("VelocityScaling");
		input("MaxDurationSec", DataTypeDefXsd.INT, "0");
		assertThrows(IllegalArgumentException.class, this::invoke);
		inputs.remove("MaxDurationSec");
		inputs.put("Joints", collection("Joints"));
		assertThrows(IllegalArgumentException.class, this::invoke);
		inputs.put("Joints", collection("Joints", property("panda_joint1", DataTypeDefXsd.FLOAT, "NaN")));
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
	}

	@Test
	public void existingClientTimeoutPolicyIsPreserved() {
		assertEquals(Duration.ofSeconds(90), RobotTwinOperationProvider.getClientTimeout(null));
		assertEquals(Duration.ofSeconds(150), RobotTwinOperationProvider.getClientTimeout(120));
	}

	@Test
	public void decimalSpeedRetainsFloatTextRepresentation() throws Exception {
		input("VelocityScaling", DataTypeDefXsd.FLOAT, "0.3");
		invoke();
		assertEquals("0.3", server.posted.path("velocity_scaling").asText());
	}

	private void invoke() throws Exception { provider.invokeSync(inputs, Map.of(), out); }
	private void input(String name, DataTypeDefXsd type, String value) { inputs.put(name, property(name, type, value)); }
	private double finalX() throws Exception {
		return Ros2SerDes.POSE_STAMPED_SERDE.toJsonNode(out.get("FinalPose")).path("pose").path("position").path("x").asDouble();
	}
	private double finalJoint() throws Exception {
		return Ros2SerDes.JOINT_STATES_SERDE.toJsonNode(out.get("FinalJoints")).path("position").path("panda_joint1").asDouble();
	}
}
