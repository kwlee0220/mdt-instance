package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.node.ObjectNode;

import utils.RuntimeInterruptedException;

import mdt.robot.client.TwinException;
import mdt.task.TaskException;

public class GetSceneObjectsProviderTest {
	private Logger logger;
	private Level previousLevel;
	private ListAppender<ILoggingEvent> logs;
	private TwinServer server;
	private GetSceneObjectsProvider provider;
	private ObjectNode envelope;
	private SubmodelElementCollection objects;
	private List<SubmodelElement> previous;
	private Map<String, SubmodelElement> out;

	@Before
	public void setUp() throws Exception {
		logger = (Logger)LoggerFactory.getLogger(GetSceneObjectsProvider.class);
		previousLevel = logger.getLevel();
		logger.setLevel(Level.INFO);
		logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		server = new TwinServer("unused");
		envelope = JSON.createObjectNode().put("quality", "OK");
		envelope.set("value", JSON.valueToTree(Map.of("objects", Map.of("box01", sceneObject()))));
		server.on("GET", "/variables/scene_objects", request -> json(200, envelope));
		server.start();
		provider = new GetSceneObjectsProvider(null, null, server.config(GetSceneObjectsProvider.class));
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
	public void readsOnlyVariableAndMapsSceneObjects() throws Exception {
		assertTrue(server.requests.isEmpty());
		invoke();
		assertEquals(List.of("GET " + BASE + "/variables/scene_objects"), server.requests);
		assertEquals(1, objects.getValue().size());
		assertTrue(logs.list.isEmpty());
		var box = (SubmodelElementCollection)objects.getValue().get(0);
		assertEquals("box01", box.getIdShort());
		assertEquals("box", ((Property)child(box, "Type")).getValue());
		var dimensions = (SubmodelElementList)child(box, "Dimensions");
		assertEquals(3, dimensions.getValue().size());
		assertEquals(0.02, Float.parseFloat(((Property)dimensions.getValue().get(0)).getValue()), 1e-7);
		var pose = (SubmodelElementCollection)child(box, "Pose");
		var position = (SubmodelElementCollection)child(pose, "Position");
		assertEquals(0.4, Float.parseFloat(((Property)child(position, "x")).getValue()), 1e-7);
		var orientation = (SubmodelElementCollection)child(pose, "Orientation");
		assertEquals(1.0, Float.parseFloat(((Property)child(orientation, "w")).getValue()), 0);
	}

	@Test
	public void emptySceneReplacesOldObjectsOnRepeatedRead() throws Exception {
		invoke();
		((ObjectNode)envelope.path("value").path("objects")).removeAll();
		invoke();
		assertTrue(objects.getValue().isEmpty());
		assertEquals(2, server.requests.size());
	}

	@Test
	public void badOrMissingQualityPreservesOutputAndReportsReason() throws Exception {
		for (String quality : List.of("NO_DATA", "ERROR", "")) {
			server.requests.clear();
			envelope.put("quality", quality).put("reason", "no fresh observation");
			TaskException failure = assertThrows(TaskException.class, this::invoke);
			assertTrue(failure.getMessage().contains("quality=" + quality));
			assertTrue(failure.getMessage().contains("reason=no fresh observation"));
			assertSame(previous, objects.getValue());
			assertEquals(1, server.requests.size());
		}
		envelope.remove("quality");
		envelope.remove("reason");
		assertThrows(TaskException.class, this::invoke);
		assertSame(previous, objects.getValue());
		envelope.putNull("quality");
		assertThrows(TaskException.class, this::invoke);
		assertSame(previous, objects.getValue());
	}

	@Test
	public void staleThenOkUsesOnlyRecoveredScene() throws Exception {
		useRetryTiming(500, 20);
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", "/variables/scene_objects", request -> json(200,
				reads.incrementAndGet() <= 2 ? Map.of("quality", "STALE", "reason", "paused") : envelope));
		invoke();
		assertEquals(3, reads.get());
		assertEquals("box01", objects.getValue().get(0).getIdShort());
		assertRecoveryLog(3);
	}

	@Test(timeout = 3000)
	public void persistentStaleStopsWithDiagnosticsAndKeepsOutput() throws Exception {
		useRetryTiming(350, 30);
		envelope.put("quality", "STALE").put("reason", "simulator paused");
		TaskException failure = assertThrows(TaskException.class, this::invoke);
		assertTrue(server.requests.size() > 1);
		assertTrue(server.requests.size() <= 12);
		assertTrue(failure.getMessage().contains("quality=STALE"));
		assertTrue(failure.getMessage().contains("reason=simulator paused"));
		assertTrue(failure.getMessage().contains("reads=" + server.requests.size()));
		assertTrue(failure.getMessage().contains("STALE retry stopped:"));
		assertTrue(failure.getMessage().contains("budget=PT0.35S"));
		assertTrue(logs.list.isEmpty());
		assertSame(previous, objects.getValue());
	}

	@Test(timeout = 1000)
	public void insufficientBudgetDoesNotSleepForUnusableLastInterval() throws Exception {
		useRetryTiming(2000, 3000);
		envelope.put("quality", "STALE");
		TaskException failure = assertThrows(TaskException.class, this::invoke);
		assertEquals(1, server.requests.size());
		assertTrue(failure.getMessage().contains("insufficient budget for next retry"));
		assertSame(previous, objects.getValue());
	}

	@Test
	public void staleThenNoDataStopsImmediately() throws Exception {
		useRetryTiming(500, 20);
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", "/variables/scene_objects", request -> json(200,
				Map.of("quality", reads.incrementAndGet() == 1 ? "STALE" : "NO_DATA", "reason", "source missing")));
		TaskException failure = assertThrows(TaskException.class, this::invoke);
		assertEquals(2, reads.get());
		assertTrue(failure.getMessage().contains("quality=NO_DATA"));
		assertFalse(failure.getMessage().contains("STALE retry stopped:"));
		assertTrue(logs.list.isEmpty());
		assertSame(previous, objects.getValue());
	}

	@Test
	public void httpErrorDuringRetryIsNotRetriedAgain() throws Exception {
		useRetryTiming(500, 20);
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", "/variables/scene_objects", request -> reads.incrementAndGet() == 1
				? json(200, Map.of("quality", "STALE"))
				: json(503, Map.of("error", Map.of("code", "UNAVAILABLE", "message", "offline"))));
		assertThrows(TwinException.class, this::invoke);
		assertEquals(2, reads.get());
		assertSame(previous, objects.getValue());
	}

	@Test(timeout = 3000)
	public void okRetryArrivingAfterBudgetIsAcceptedAndLoggedOnce() throws Exception {
		useRetryTiming(300, 20);
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", "/variables/scene_objects", request -> reads.incrementAndGet() == 1
				? json(200, Map.of("quality", "STALE"))
				: json(200, envelope).setBodyDelay(350, TimeUnit.MILLISECONDS));
		invoke();
		assertEquals(2, reads.get());
		assertEquals("box01", objects.getValue().get(0).getIdShort());
		assertRecoveryLog(2);
	}

	@Test(timeout = 3000)
	public void staleResponseAfterBudgetDoesNotStartAnotherRequest() throws Exception {
		useRetryTiming(300, 20);
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", "/variables/scene_objects", request -> {
			var response = json(200, Map.of("quality", "STALE", "reason", "paused"));
			return reads.incrementAndGet() == 1 ? response : response.setBodyDelay(350, TimeUnit.MILLISECONDS);
		});
		TaskException failure = assertThrows(TaskException.class, this::invoke);
		assertEquals(2, reads.get());
		assertTrue(failure.getMessage().contains("budget exhausted"));
		assertSame(previous, objects.getValue());
		assertTrue(logs.list.isEmpty());
	}

	@Test(timeout = 5000)
	public void interruptionDuringRetrySleepPreservesSignalAndStopsRequests() throws Exception {
		envelope.put("quality", "STALE");
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread worker = new Thread(() -> {
			try { invoke(); }
			catch (Throwable e) { thrown.set(e); }
			finally { interrupted.set(Thread.currentThread().isInterrupted()); }
		}, "provider-scene-retry-interruption");
		worker.start();
		try {
			long due = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			boolean sleeping = false;
			while (System.nanoTime() < due) {
				if (worker.getState() == Thread.State.TIMED_WAITING
						&& java.util.Arrays.stream(worker.getStackTrace()).anyMatch(frame ->
								frame.getClassName().equals(TimeUnit.class.getName()) && frame.getMethodName().equals("sleep"))) {
					sleeping = true;
					break;
				}
				Thread.sleep(5);
			}
			assertTrue("worker should be sleeping between STALE reads", sleeping);
			int reads = server.requests.size();
			worker.interrupt();
			worker.join(1000);
			assertFalse(worker.isAlive());
			assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof RuntimeInterruptedException);
			assertTrue(thrown.get().getCause() instanceof InterruptedException);
			assertTrue(interrupted.get());
			assertEquals(reads, server.requests.size());
			assertSame(previous, objects.getValue());
		}
		finally {
			worker.interrupt();
			worker.join(1000);
		}
	}

	@Test
	public void missingOrInvalidObjectMapPreservesOutput() throws Exception {
		for (var value : List.of(JSON.nullNode(), JSON.createObjectNode(),
				JSON.createObjectNode().putNull("objects"),
				JSON.createObjectNode().set("objects", JSON.createArrayNode()))) {
			envelope.set("value", value);
			assertThrows(IOException.class, this::invoke);
			assertSame(previous, objects.getValue());
		}
		envelope.remove("value");
		assertThrows(IOException.class, this::invoke);
		assertSame(previous, objects.getValue());
	}

	@Test
	public void laterInvalidObjectDoesNotPartiallyUpdateOutput() throws Exception {
		ObjectNode map = JSON.createObjectNode();
		map.set("validBox", JSON.valueToTree(sceneObject()));
		map.set("invalid-name", JSON.valueToTree(sceneObject()));
		((ObjectNode)envelope.path("value")).set("objects", map);
		assertThrows(IOException.class, this::invoke);
		assertSame(previous, objects.getValue());
	}

	@Test
	public void httpErrorsExposeClientExceptionWithoutOutputUpdate() throws Exception {
		for (int status : new int[]{404, 503}) {
			server.on("GET", "/variables/scene_objects", request -> json(status,
					Map.of("error", Map.of("code", "UNAVAILABLE", "message", "scene unavailable"))));
			TwinException failure = assertThrows(TwinException.class, this::invoke);
			assertEquals(status, failure.getHttpStatus());
			assertEquals("UNAVAILABLE", failure.getCode());
			assertSame(previous, objects.getValue());
		}
	}

	@Test
	public void invalidOutputBindingFailsBeforeCommunication() throws Exception {
		out.clear();
		assertThrows(IllegalArgumentException.class, this::invoke);
		out.put("Objects", property("Objects", DataTypeDefXsd.STRING, "wrong type"));
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
	}

	@Test(timeout = 10000)
	public void interruptedReadPreservesSignalAndOutput() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		server.on("GET", "/variables/scene_objects", request -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return json(200, envelope.deepCopy().put("padding", "x".repeat(65536)));
		});
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread worker = new Thread(() -> {
			try { invoke(); }
			catch (Throwable e) { thrown.set(e); }
			finally { interrupted.set(Thread.currentThread().isInterrupted()); }
		}, "provider-scene-interruption");
		worker.start();
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			worker.interrupt();
			release.countDown();
			worker.join(3000);
			assertFalse(worker.isAlive());
			assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof RuntimeInterruptedException);
			assertTrue(thrown.get().getCause() instanceof InterruptedException);
			assertTrue(interrupted.get());
			assertSame(previous, objects.getValue());
		}
		finally {
			release.countDown();
			worker.interrupt();
			worker.join(3000);
		}
	}

	private void useRetryTiming(long budgetMillis, long intervalMillis) throws Exception {
		provider = new GetSceneObjectsProvider(null, null, server.config(GetSceneObjectsProvider.class),
				Duration.ofMillis(budgetMillis), Duration.ofMillis(intervalMillis));
	}

	private void assertRecoveryLog(int reads) {
		assertEquals(1, logs.list.size());
		var event = logs.list.get(0);
		assertEquals(Level.INFO, event.getLevel());
		String message = event.getFormattedMessage();
		assertTrue(message.contains("scene_objects quality recovered"));
		assertTrue(message.contains("twin=panda01"));
		assertTrue(message.contains("reads=" + reads));
		assertTrue(message.matches(".*elapsed_ms=[0-9]+.*"));
	}

	private void invoke() throws Exception { provider.invokeSync(Map.of(), Map.of(), out); }
	private static SubmodelElement child(SubmodelElementCollection collection, String name) {
		return collection.getValue().stream().filter(e -> name.equals(e.getIdShort())).findFirst().orElseThrow();
	}
	private static Map<String, Object> sceneObject() {
		return Map.of("type", "box", "dimensions", List.of(0.02, 0.03, 0.04),
				"pose", Map.of("position", Map.of("x", 0.4, "y", -0.1, "z", 0.2),
						"orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
	}
}
