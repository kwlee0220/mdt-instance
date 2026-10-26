package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
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
import mdt.robot.client.operation.WaitForFreshVariable;
import mdt.task.TaskException;

public class ResetSceneProviderTest {
	private TwinServer server;
	private ResetSceneProvider provider;
	private Map<String, SubmodelElement> inputs;
	private Map<String, SubmodelElement> out;
	private SubmodelElementCollection objects;
	private List<SubmodelElement> previous;
	private final AtomicInteger observations = new AtomicInteger();
	private Logger logger;
	private Level previousLevel;
	private ListAppender<ILoggingEvent> logs;

	@Before
	public void setUp() throws Exception {
		logger = (Logger)LoggerFactory.getLogger(ResetSceneProvider.class);
		previousLevel = logger.getLevel();
		logger.setLevel(Level.WARN);
		logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		server = new TwinServer("reset_scene");
		server.outputs.removeAll();
		server.outputs.set("objects", JSON.valueToTree(List.of(placement("box01"))));
		server.outputs.put("applied_count", 1);
		server.on("GET", "/variables/scene_objects", request -> json(200, Map.of(
				"quality", "OK", "received_at", Instant.EPOCH.plusSeconds(observations.incrementAndGet()).toString(),
				"value", Map.of("objects", Map.of()))));
		server.start();
		provider = new ResetSceneProvider(null, null, server.config(ResetSceneProvider.class));
		inputs = new LinkedHashMap<>(Map.of("SceneName", property("SceneName", DataTypeDefXsd.STRING, " initial ")));
		objects = collection("Objects", collection("oldObject"));
		previous = objects.getValue();
		out = new LinkedHashMap<>(Map.of("Objects", objects));
	}

	@After public void tearDown() throws Exception {
		try { server.close(); }
		finally {
			logger.detachAppender(logs);
			logger.setLevel(previousLevel);
			logs.stop();
		}
	}

	@Test
	public void resetUsesTrimmedNameAndWaitsForTwoUpdatesBeforeMappingCommandedPlacement() throws Exception {
		assertTrue(server.requests.isEmpty());
		invoke();
		assertEquals("initial", server.posted.path("scene").asText());
		assertEquals(1, server.posted.size());
		assertEquals(3, observations.get()); // 기준 표본 + 갱신 2회.
		assertEquals("box01", objects.getValue().get(0).getIdShort());
		var box = (SubmodelElementCollection)objects.getValue().get(0);
		assertEquals("box", ((Property)box.getValue().get(0)).getValue());
		assertEquals("POST " + BASE + "/operations/reset_scene", server.requests.get(1));
		assertTrue(logs.list.isEmpty());
	}

	@Test
	public void asyncCatalogAndSignedSeedAreSupported() throws Exception {
		server.async = true;
		inputs.put("Seed", property("Seed", DataTypeDefXsd.INT, "-7"));
		invoke();
		assertEquals(-7, server.posted.path("seed").longValue());
		assertTrue(server.requests.contains("GET " + BASE + "/operations/sessions/test"));
		assertEquals(1, server.requests.stream().filter(r -> r.startsWith("POST ")).count());
		assertEquals(3, observations.get());
	}

	@Test
	public void optionalOutputDoesNotSkipObservation() throws Exception {
		out.clear();
		invoke();
		assertEquals(3, observations.get());
		assertTrue(logs.list.isEmpty());
	}

	@Test
	public void emptyPlacementWithZeroAppliedCountIsValid() throws Exception {
		server.outputs.set("objects", JSON.createArrayNode());
		server.outputs.put("applied_count", 0);
		invoke();
		assertTrue(objects.getValue().isEmpty());
		assertEquals(3, observations.get());
	}

	@Test
	public void incompleteApplicationFailsBeforeObservationAndOutputUpdate() throws Exception {
		for (boolean bindOutput : new boolean[]{true, false}) {
			if (!bindOutput) { out.clear(); }
			for (var count : List.of(JSON.getNodeFactory().numberNode(0), JSON.nullNode(),
					JSON.getNodeFactory().textNode("1"))) {
				server.outputs.set("applied_count", count);
				TaskException failure = assertThrows(TaskException.class, this::invoke);
				assertTrue(failure.getMessage().contains("application could not be confirmed"));
				assertSame(previous, objects.getValue());
				assertEquals(0, observations.get());
			}
		}
	}

	@Test
	public void invalidPlacementFailsBeforeObservationAndOutputUpdate() throws Exception {
		for (var value : List.of(JSON.nullNode(), JSON.createObjectNode(), JSON.valueToTree(List.of(Map.of("name", "box01"))))) {
			server.outputs.set("objects", value);
			assertThrows(TaskException.class, this::invoke);
			assertSame(previous, objects.getValue());
			assertEquals(0, observations.get());
		}
	}

	@Test
	public void remoteFailureAndCancellationAreNotObservedOrMapped() throws Exception {
		for (boolean async : new boolean[]{false, true}) {
			server.async = async;
			provider = new ResetSceneProvider(null, null, server.config(ResetSceneProvider.class));
			for (String status : List.of("FAILED", "CANCELLED")) {
				logs.list.clear();
				server.status = status;
				var failure = assertThrows(OperationFailedException.class, this::invoke);
				assertEquals(status, failure.getResult().status());
				assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("status=" + status)
						&& e.getFormattedMessage().contains("operation=reset_scene")));
				assertEquals(0, observations.get());
				assertSame(previous, objects.getValue());
			}
		}
	}

	@Test
	public void rejectionIsNotRetried() throws Exception {
		server.rejection = 409;
		var failure = assertThrows(TwinException.class, this::invoke);
		assertEquals("RESOURCE_BUSY", failure.getCode());
		assertEquals(1, server.requests.stream().filter(r -> r.startsWith("POST ")).count());
		assertEquals(0, observations.get());
		assertSame(previous, objects.getValue());
	}

	@Test
	public void invalidSceneIsRejectedBeforeCommunication() throws Exception {
		for (String name : new String[]{null, "", " "}) {
			inputs.put("SceneName", property("SceneName", DataTypeDefXsd.STRING, name));
			assertThrows(IllegalArgumentException.class, this::invoke);
		}
		inputs.clear();
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
	}

	@Test
	public void missingTimeStaleAndUnchangedTimeWarnButKeepCommandedOutput() throws Exception {
		provider = new ResetSceneProvider(null, null, server.config(ResetSceneProvider.class),
				new WaitForFreshVariable.Options(2, Duration.ofMillis(250), Duration.ofMillis(20)));
		var samples = List.of(
				Map.of("quality", "OK", "value", Map.of()),
				Map.of("quality", "STALE", "value", Map.of(), "received_at", Instant.EPOCH.toString()),
				Map.of("quality", "OK", "value", Map.of(), "received_at", Instant.EPOCH.toString()));
		for (int i = 0; i < samples.size(); ++i) {
			logs.list.clear();
			var sample = samples.get(i);
			server.on("GET", "/variables/scene_objects", request -> json(200, sample));
			invoke();
			assertEquals("box01", objects.getValue().get(0).getIdShort());
			assertEquals(1, logs.list.size());
			assertEquals(Level.WARN, logs.list.get(0).getLevel());
			assertTrue(logs.list.get(0).getFormattedMessage().contains(
					List.of("NO_VALID_TIMESTAMP", "NO_VALID_SAMPLE", "TIMEOUT").get(i)));
		}
	}

	@Test
	public void observationHttpFailurePreservesOutputAndDoesNotRepeatReset() throws Exception {
		server.on("GET", "/variables/scene_objects", request -> json(503,
				Map.of("error", Map.of("code", "UNAVAILABLE", "message", "offline"))));
		assertThrows(TwinException.class, this::invoke);
		assertSame(previous, objects.getValue());
		assertEquals(1, server.requests.stream().filter(r -> r.startsWith("POST ")).count());
	}

	@Test
	public void aasConversionFailureKeepsPreviousObjects() throws Exception {
		server.outputs.set("objects", JSON.valueToTree(List.of(placement("invalid-name"))));
		assertThrows(IOException.class, this::invoke);
		assertSame(previous, objects.getValue());
	}

	@Test(timeout = 10000)
	public void observationInterruptionPropagatesWithoutOutputUpdate() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		server.on("GET", "/variables/scene_objects", request -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return json(200, Map.of("quality", "OK", "value", Map.of(), "padding", "x".repeat(65536)));
		});
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try { invoke(); }
			catch (Throwable e) { thrown.set(e); }
		}, "provider-reset-observation-interruption");
		worker.start();
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			worker.interrupt();
			release.countDown();
			worker.join(3000);
			assertFalse(worker.isAlive());
			assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof InterruptedException);
			assertSame(previous, objects.getValue());
			assertEquals(1, server.requests.stream().filter(r -> r.startsWith("POST ")).count());
		}
		finally {
			release.countDown();
			worker.interrupt();
			worker.join(3000);
		}
	}

	private void invoke() throws Exception { provider.invokeSync(inputs, Map.of(), out); }
	private static Map<String, Object> placement(String name) {
		return Map.of("name", name, "type", "box", "dimensions", List.of(0.02, 0.03, 0.04),
				"position", Map.of("x", 0.4, "y", 0.1, "z", 0.2));
	}
}
