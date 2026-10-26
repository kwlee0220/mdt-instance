package mdt.assetconnection.operation.robot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementCollection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import mdt.assetconnection.operation.JavaOperationProviderConfig;

/** Provider 테스트의 AAS 값 생성과 로봇 트윈 HTTP 응답 지원. 테스트마다 새 서버를 사용한다. */
final class RobotTwinProviderTestSupport {
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String BASE = "/api/v1/robot_twins/panda01";

	private RobotTwinProviderTestSupport() { }

	@FunctionalInterface
	interface ResponseHandler {
		MockResponse respond(RecordedRequest request) throws InterruptedException;
	}

	/** 기본 연산 경로와 테스트가 등록한 추가 경로를 처리한다. */
	static final class TwinServer implements AutoCloseable {
		private final MockWebServer server = new MockWebServer();
		private final String operation;
		private final Map<String,ResponseHandler> handlers = new ConcurrentHashMap<>();
		final List<String> requests = new CopyOnWriteArrayList<>();
		final ObjectNode outputs = JSON.createObjectNode();
		volatile JsonNode posted;
		volatile String status = "COMPLETED";
		volatile boolean async;
		volatile int rejection;

		TwinServer(String operation) {
			this.operation = operation;
			outputs.put("closed_loop", true);
			outputs.set("final_pose", stamped(0.4));
			outputs.set("final_joints", JSON.valueToTree(Map.of("position", Map.of("panda_joint1", 0.2))));
			server.setDispatcher(new Dispatcher() {
				@Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
					return TwinServer.this.dispatch(request);
				}
			});
		}

		void on(String method, String path, ResponseHandler handler) {
			handlers.put(method + " " + BASE + path, handler);
		}

		void start() throws IOException { server.start(); }
		@Override public void close() throws IOException { server.shutdown(); }

		JavaOperationProviderConfig config(Class<?> providerClass) {
			return new JavaOperationProviderConfig(providerClass.getName(), Map.of("robotTwinConfig",
					JSON.valueToTree(Map.of("robotTwinBaseUrl", server.url("/").toString(), "twinId", "panda01"))));
		}

		private MockResponse dispatch(RecordedRequest request) throws InterruptedException {
			String key = request.getMethod() + " " + request.getPath();
			requests.add(key);
			ResponseHandler handler = handlers.get(key);
			if (handler != null) { return handler.respond(request); }
			if (key.equals("GET " + BASE + "/operations")) {
				return json(200, Map.of("operations", List.of(Map.of("name", operation, "kind", async ? "async" : "sync"))));
			}
			if (key.equals("POST " + BASE + "/operations/" + operation)) {
				try { posted = JSON.readTree(request.getBody().readUtf8()).path("inputs"); }
				catch (IOException e) { throw new AssertionError(e); }
				if (rejection != 0) { return json(rejection, Map.of("error", Map.of("code", "RESOURCE_BUSY", "message", "busy"))); }
				if (async) { return json(202, Map.of("status", "RUNNING", "session_endpoint", BASE + "/operations/sessions/test")); }
				return result();
			}
			if (key.equals("GET " + BASE + "/operations/sessions/test")) { return result(); }
			return json(404, Map.of("error", Map.of("code", "NOT_FOUND", "message", key)));
		}

		private MockResponse result() {
			return json(200, Map.of("status", status, "outputs", outputs,
					"error", Map.of("code", "PLANNING_FAILED", "message", "no plan")));
		}
	}

	static JsonNode stamped(double x) {
		return JSON.valueToTree(Map.of("header", Map.of("stamp", Map.of("sec", 10, "nanosec", 0), "frame_id", "panda_link0"),
				"pose", Map.of("position", Map.of("x", x, "y", 0.0, "z", 0.1),
						"orientation", Map.of("x", 1.0, "y", 0.0, "z", 0.0, "w", 0.0))));
	}
	static SubmodelElementCollection pose(String name, double x) {
		return collection(name, collection("Position", property("x", DataTypeDefXsd.FLOAT, ""+x),
				property("y", DataTypeDefXsd.FLOAT, "0"), property("z", DataTypeDefXsd.FLOAT, "0.1")),
				collection("Orientation", property("x", DataTypeDefXsd.FLOAT, "1"), property("y", DataTypeDefXsd.FLOAT, "0"),
						property("z", DataTypeDefXsd.FLOAT, "0"), property("w", DataTypeDefXsd.FLOAT, "0")));
	}
	static SubmodelElementCollection collection(String name, SubmodelElement... children) {
		return new DefaultSubmodelElementCollection.Builder().idShort(name).value(new ArrayList<>(List.of(children))).build();
	}
	static Property property(String name, DataTypeDefXsd type, String value) {
		return new DefaultProperty.Builder().idShort(name).valueType(type).value(value).build();
	}
	static MockResponse json(int status, Object body) {
		try { return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(JSON.writeValueAsString(body)); }
		catch (Exception e) { throw new AssertionError(e); }
	}
}
