package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

import mdt.robot.client.OperationFailedException;
import mdt.robot.client.TwinException;

public class MoveGripperToTargetProviderTest {
	private TwinServer server;
	private MoveGripperToTargetProvider provider;
	private Map<String, SubmodelElement> inputs;
	private Map<String, SubmodelElement> out;
	private Logger logger;
	private Level previousLevel;
	private ListAppender<ILoggingEvent> logs;

	@Before
	public void setUp() throws Exception {
		logger = (Logger)LoggerFactory.getLogger(MoveGripperToTargetProvider.class);
		previousLevel = logger.getLevel();
		logger.setLevel(Level.WARN);
		logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		server = new TwinServer("move_gripper_to_target");
		server.outputs.removeAll();
		server.outputs.put("target", "grasp");
		server.outputs.put("width", 0.025);
		server.outputs.put("stalled", true);
		server.outputs.put("at_goal", true);
		server.start();
		provider = new MoveGripperToTargetProvider(null, null, server.config(MoveGripperToTargetProvider.class));
		inputs = new LinkedHashMap<>();
		inputs.put("Target", property("Target", DataTypeDefXsd.STRING, "grasp"));
		out = new LinkedHashMap<>();
		out.put("Pose", property("Pose", DataTypeDefXsd.STRING, "old"));
		out.put("Position", property("Position", DataTypeDefXsd.FLOAT, "-1"));
		out.put("Effort", property("Effort", DataTypeDefXsd.FLOAT, "-1"));
		out.put("Stalled", property("Stalled", DataTypeDefXsd.BOOLEAN, "false"));
		out.put("ReachedGoal", property("ReachedGoal", DataTypeDefXsd.BOOLEAN, "false"));
	}

	@After
	public void tearDown() throws Exception {
		try { server.close(); }
		finally {
			logger.detachAppender(logs);
			logger.setLevel(previousLevel);
			logs.stop();
		}
	}

	@Test
	public void syncCallMapsOutputsAndSendsOnlyTarget() throws Exception {
		assertTrue(server.requests.isEmpty());
		server.outputs.put("effort", 2.5);
		invoke();
		assertEquals("grasp", server.posted.path("target").asText());
		assertEquals(1, server.posted.size());
		assertEquals("grasp", value("Pose"));
		assertEquals(0.025, Double.parseDouble(value("Position")), 1e-6);
		assertEquals(2.5, Double.parseDouble(value("Effort")), 1e-6);
		assertEquals("true", value("Stalled"));
		assertEquals("true", value("ReachedGoal"));
		assertEquals(List.of("GET " + BASE + "/operations", "POST " + BASE + "/operations/move_gripper_to_target"), server.requests);
	}

	@Test
	public void asyncCatalogUsesSessionPolling() throws Exception {
		server.async = true;
		invoke();
		assertEquals("true", value("ReachedGoal"));
		assertTrue(server.requests.contains("GET " + BASE + "/operations/sessions/test"));
		assertEquals(1, server.requests.stream().filter(r -> r.startsWith("POST ")).count());
	}

	@Test
	public void stalledDoesNotOverrideFalseAtGoal() throws Exception {
		server.outputs.put("at_goal", false);
		invoke();
		assertEquals("true", value("Stalled"));
		assertEquals("false", value("ReachedGoal"));
		assertEquals("grasp", value("Pose"));
		assertTrue(logs.list.isEmpty());
	}

	@Test
	public void optionalWidthAndEffortKeepExistingValues() throws Exception {
		server.outputs.putNull("width");
		invoke();
		assertEquals("-1", value("Position"));
		assertEquals("-1", value("Effort"));
		server.outputs.remove("width");
		server.outputs.putNull("effort");
		out.remove("Effort");
		invoke();
		assertEquals("-1", value("Position"));
		assertEquals("true", value("ReachedGoal"));
	}

	@Test
	public void legacyAliasesRemainSupportedIncludingNullPrimaryValues() throws Exception {
		for (boolean explicitNull : new boolean[]{false, true}) {
			logs.list.clear();
			for (String key : List.of("target", "width", "at_goal")) {
				if (explicitNull) { server.outputs.putNull(key); }
				else { server.outputs.remove(key); }
			}
			server.outputs.put("goal", "close");
			server.outputs.put("position", 0.01);
			server.outputs.put("reached_goal", true);
			invoke();
			assertEquals("close", value("Pose"));
			assertEquals(0.01, Double.parseDouble(value("Position")), 1e-6);
			assertEquals("true", value("ReachedGoal"));
			assertTrue(logs.list.isEmpty());
		}
	}

	@Test
	public void malformedBooleansWarnWithFieldAndValueAndBecomeFalse() throws Exception {
		for (String field : List.of("stalled", "at_goal", "reached_goal")) {
			for (var malformed : List.of(JSON.getNodeFactory().textNode("true"),
					JSON.getNodeFactory().numberNode(1))) {
				logs.list.clear();
				server.outputs.put("stalled", true);
				server.outputs.put("at_goal", true);
				server.outputs.remove("reached_goal");
				if (field.equals("reached_goal")) { server.outputs.remove("at_goal"); }
				server.outputs.set(field, malformed);
				invoke();
				assertEquals(field.equals("stalled") ? "false" : "true", value("Stalled"));
				assertEquals(field.equals("stalled") ? "true" : "false", value("ReachedGoal"));
				assertEquals(1, logs.list.size());
				var event = logs.list.get(0);
				assertEquals(Level.WARN, event.getLevel());
				assertTrue(event.getFormattedMessage().contains("field=" + field));
				assertTrue(event.getFormattedMessage().contains("value=" + malformed));
				assertTrue(event.getFormattedMessage().contains("twin=panda01"));
				assertTrue(event.getFormattedMessage().contains("operation=move_gripper_to_target"));
			}
		}
	}

	@Test
	public void malformedPrimaryBooleanDoesNotFallBackToValidAlias() throws Exception {
		server.outputs.put("at_goal", "true");
		server.outputs.put("reached_goal", true);
		invoke();
		assertEquals("false", value("ReachedGoal"));
		assertEquals(1, logs.list.size());
		assertTrue(logs.list.get(0).getFormattedMessage().contains("field=at_goal"));
	}

	@Test
	public void primaryOutputsTakePrecedenceOverAliases() throws Exception {
		server.outputs.put("goal", "close");
		server.outputs.put("position", 0.01);
		server.outputs.put("reached_goal", false);
		invoke();
		assertEquals("grasp", value("Pose"));
		assertEquals(0.025, Double.parseDouble(value("Position")), 1e-6);
		assertEquals("true", value("ReachedGoal"));
	}

	@Test
	public void missingRequiredOutputLeavesAllOutputsUntouched() throws Exception {
		for (String name : List.of("target", "stalled", "at_goal")) {
			var saved = server.outputs.remove(name);
			assertThrows(IOException.class, this::invoke);
			assertUnchanged();
			server.outputs.putNull(name);
			assertThrows(IOException.class, this::invoke);
			assertUnchanged();
			server.outputs.set(name, saved);
		}
	}

	@Test
	public void remoteFailureAndCancellationThrowBeforeOutputUpdate() throws Exception {
		for (boolean async : new boolean[]{false, true}) {
			server.async = async;
			provider = new MoveGripperToTargetProvider(null, null, server.config(MoveGripperToTargetProvider.class));
			for (String status : List.of("FAILED", "CANCELLED")) {
				logs.list.clear();
				server.status = status;
				var failure = assertThrows(OperationFailedException.class, this::invoke);
				assertEquals(status, failure.getResult().status());
				assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
						&& e.getFormattedMessage().contains("status=" + status)
						&& e.getFormattedMessage().contains("operation=move_gripper_to_target")
						&& e.getFormattedMessage().contains("twin=panda01")));
				assertUnchanged();
			}
		}
	}

	@Test
	public void rejectionExposesOriginalTwinException() throws Exception {
		for (int code : new int[]{400, 409}) {
			server.rejection = code;
			var failure = assertThrows(TwinException.class, this::invoke);
			assertEquals(code, failure.getHttpStatus());
			assertUnchanged();
		}
	}

	@Test
	public void invalidTargetIsRejectedBeforeCommunication() throws Exception {
		for (String target : new String[]{null, "", " ", " open", "open "}) {
			inputs.put("Target", property("Target", DataTypeDefXsd.STRING, target));
			assertThrows(IllegalArgumentException.class, this::invoke);
		}
		inputs.remove("Target");
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
	}

	@Test(timeout = 10000)
	public void interruptionDuringSyncHttpIsPropagatedBeforeOutputUpdate() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		server.on("POST", "/operations/move_gripper_to_target", request -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			// 큰 응답을 읽는 도중에도 Okio가 인터럽트를 확인하게 한다.
			return json(200, Map.of("status", "COMPLETED", "outputs", server.outputs, "padding", "x".repeat(65536)));
		});
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try { invoke(); }
			catch (Throwable e) { thrown.set(e); }
		}, "provider-gripper-interruption");
		worker.start();
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			worker.interrupt();
			release.countDown();
			worker.join(3000);
			assertFalse(worker.isAlive());
			assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof InterruptedException);
			assertUnchanged();
		}
		finally {
			release.countDown();
			worker.interrupt();
			worker.join(3000);
		}
	}

	private void invoke() throws Exception { provider.invokeSync(inputs, Map.of(), out); }
	private String value(String name) { return ((Property)out.get(name)).getValue(); }
	private void assertUnchanged() {
		assertEquals("old", value("Pose"));
		assertEquals("-1", value("Position"));
		assertEquals("-1", value("Effort"));
		assertEquals("false", value("Stalled"));
		assertEquals("false", value("ReachedGoal"));
	}
}
