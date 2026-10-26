package mdt.assetconnection.operation.robot;

import static org.junit.Assert.*;
import static mdt.assetconnection.operation.robot.RobotTwinProviderTestSupport.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.mockwebserver.MockResponse;

import mdt.robot.client.TwinException;

public class WaitUntilObjectStillProviderTest {
	private TwinServer server;
	private WaitUntilObjectStillProvider provider;
	private Map<String, SubmodelElement> inputs;
	private Map<String, SubmodelElement> out;
	private SubmodelElementCollection objects;
	private List<SubmodelElement> previous;
	private final AtomicInteger reads = new AtomicInteger();
	/** 읽은 횟수(1부터)에 따라 응답을 정한다. 기본은 정지한 peg. */
	private volatile ReadResponder responder = read -> scene("OK", 0.5);

	@FunctionalInterface
	private interface ReadResponder { MockResponse respond(int read); }

	@Before
	public void setUp() throws Exception {
		server = new TwinServer("unused");
		server.on("GET", "/variables/scene_objects", request -> responder.respond(reads.incrementAndGet()));
		server.start();
		provider = new WaitUntilObjectStillProvider(null, null, server.config(WaitUntilObjectStillProvider.class));
		inputs = new LinkedHashMap<>();
		input("ObjectName", DataTypeDefXsd.STRING, "peg");
		input("Timeout", DataTypeDefXsd.DOUBLE, "3");
		input("QuietPeriod", DataTypeDefXsd.DOUBLE, "0.2");
		objects = collection("Objects", collection("oldObject"));
		previous = objects.getValue();
		out = new LinkedHashMap<>();
		out.put("Settled", property("Settled", DataTypeDefXsd.BOOLEAN, "old"));
		out.put("Outcome", property("Outcome", DataTypeDefXsd.STRING, "old"));
		out.put("Objects", objects);
		out.put("Diagnostics", property("Diagnostics", DataTypeDefXsd.STRING, "old"));
	}

	@After public void tearDown() throws Exception { server.close(); }

	@Test(timeout = 10000)
	public void stillObjectSettlesAndReturnsObservedScene() throws Exception {
		invoke();
		assertEquals("true", value("Settled"));
		assertEquals("SETTLED", value("Outcome"));
		assertEquals(List.of("peg", "hole"), objects.getValue().stream().map(SubmodelElement::getIdShort).toList());
		assertTrue(value("Diagnostics").startsWith("SETTLED: peg"));
		assertTrue(server.requests.stream().allMatch(r -> r.equals("GET " + BASE + "/variables/scene_objects")));
	}

	@Test(timeout = 10000)
	public void unconvertibleSceneNameKeepsObjectsButReturnsSettledResult() throws Exception {
		responder = read -> scene("OK", 0.5, "another-hole");
		invoke();
		assertEquals("true", value("Settled"));
		assertEquals("SETTLED", value("Outcome"));
		assertEquals(previous, objects.getValue());
	}

	@Test(timeout = 10000)
	public void movingObjectTimesOutNormallyWithLastValidScene() throws Exception {
		input("Timeout", DataTypeDefXsd.DOUBLE, "0.5");
		responder = read -> scene("OK", 0.5 + read * 0.001);
		invoke();
		assertEquals("false", value("Settled"));
		assertEquals("TIMEOUT", value("Outcome"));
		// 정착의 근거는 아니지만 마지막 유효 응답의 물체로 갱신한다.
		assertEquals("peg", objects.getValue().get(0).getIdShort());
		assertTrue(value("Diagnostics").contains("POSITION_CHANGED"));
	}

	@Test(timeout = 10000)
	public void staleOnlyKeepsObjectsAndReportsNoValidSample() throws Exception {
		input("Timeout", DataTypeDefXsd.DOUBLE, "0.4");
		responder = read -> scene("STALE", 0.5);
		invoke();
		assertEquals("false", value("Settled"));
		assertEquals("NO_VALID_SAMPLE", value("Outcome"));
		assertSame(previous, objects.getValue());
		assertTrue(value("Diagnostics").contains("QUALITY_NOT_OK"));
	}

	@Test(timeout = 10000)
	public void missingObjectIsReportedAsNoValidSample() throws Exception {
		input("ObjectName", DataTypeDefXsd.STRING, "cube");
		input("Timeout", DataTypeDefXsd.DOUBLE, "0.4");
		invoke();
		assertEquals("NO_VALID_SAMPLE", value("Outcome"));
		assertTrue(value("Diagnostics").contains("OBJECT_MISSING"));
		assertSame(previous, objects.getValue());
	}

	@Test(timeout = 10000)
	public void publicationPauseRestartsQuietWindowThenSettles() throws Exception {
		// 펑션베이의 주기적 정지처럼 정착 도중 STALE이 끼어도 재개 후 다시 정착을 확인한다.
		responder = read -> scene(read >= 2 && read <= 4 ? "STALE" : "OK", 0.5);
		invoke();
		assertEquals("SETTLED", value("Outcome"));
		assertTrue(value("Diagnostics"), value("Diagnostics").contains("마지막 재시작=INVALID_SAMPLE"));
	}

	@Test
	public void invalidInputsFailBeforeCommunication() throws Exception {
		for (String[] invalid : List.of(new String[]{"Timeout", "DOUBLE", "0"}, new String[]{"Timeout", "DOUBLE", "-1"},
				new String[]{"PositionTolerance", "DOUBLE", "NaN"}, new String[]{"AngleToleranceDeg", "DOUBLE", "181"},
				new String[]{"QuietPeriod", "DOUBLE", "0"}, new String[]{"ObjectName", "STRING", " peg"})) {
			Map<String, SubmodelElement> saved = new LinkedHashMap<>(inputs);
			input(invalid[0], DataTypeDefXsd.valueOf(invalid[1]), invalid[2]);
			assertThrows(invalid[0] + "=" + invalid[2], IllegalArgumentException.class, this::invoke);
			inputs = saved;
		}
		inputs.remove("ObjectName");
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
		assertEquals("old", value("Outcome"));
		assertSame(previous, objects.getValue());
	}

	@Test
	public void httpErrorPropagatesWithoutOutputUpdate() throws Exception {
		responder = read -> json(503, Map.of("error", Map.of("code", "UNAVAILABLE", "message", "offline")));
		TwinException failure = assertThrows(TwinException.class, this::invoke);
		assertEquals(503, failure.getHttpStatus());
		assertEquals("old", value("Settled"));
		assertSame(previous, objects.getValue());
	}

	@Test(timeout = 10000)
	public void optionalOutputsMayBeOmitted() throws Exception {
		out.remove("Objects");
		out.remove("Diagnostics");
		invoke();
		assertEquals("SETTLED", value("Outcome"));
	}

	@Test
	public void missingRequiredOutputFailsBeforeCommunication() throws Exception {
		out.remove("Outcome");
		assertThrows(IllegalArgumentException.class, this::invoke);
		assertTrue(server.requests.isEmpty());
	}

	private static MockResponse scene(String quality, double pegX, String... extraNames) {
		Map<String, Object> peg = Map.of("type", "cylinder", "dimensions", List.of(0.05, 0.008),
				"pose", Map.of("position", Map.of("x", pegX, "y", 0.0, "z", 0.03),
						"orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
		Map<String, Object> hole = Map.of("type", "box", "dimensions", List.of(0.04, 0.04, 0.025),
				"pose", Map.of("position", Map.of("x", 0.5, "y", 0.0, "z", 0.003),
						"orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
		Map<String, Object> objects = new LinkedHashMap<>();
		objects.put("peg", peg);
		objects.put("hole", hole);
		for ( String name: extraNames ) {
			objects.put(name, hole);
		}
		return json(200, Map.of("quality", quality, "received_at", Instant.now().toString(),
				"value", Map.of("header", Map.of("frame_id", "panda_link0"), "objects", objects)));
	}

	private void invoke() throws Exception { provider.invokeSync(inputs, Map.of(), out); }
	private String value(String name) { return ((Property)out.get(name)).getValue(); }
	private void input(String name, DataTypeDefXsd type, String value) { inputs.put(name, property(name, type, value)); }
}
