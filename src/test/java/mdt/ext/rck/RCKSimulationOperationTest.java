package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.junit.After;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import mdt.assetconnection.operation.JavaOperationProviderConfig;


/**
 * {@link RCKSimulationOperation}의 인자 처리, 출력 기록, 대기 중단 시 취소를 가짜 RCK 서버로 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationOperationTest {
	private MockWebServer m_server;

	@After
	public void tearDown() throws Exception {
		if ( m_server != null ) {
			m_server.close();
		}
	}

	private String startServer(FakeRckServer server) throws Exception {
		m_server = new MockWebServer();
		m_server.enqueue(new MockResponse().withWebSocketUpgrade(server));
		m_server.start();
		return "ws://" + m_server.getHostName() + ":" + m_server.getPort() + "/rck";
	}

	private static JavaOperationProviderConfig config(String url, Map<String,String> extra) {
		Map<String,JsonNode> args = new HashMap<>();
		args.put("clientId", TextNode.valueOf("test-client"));
		args.put("processName", TextNode.valueOf("press"));
		args.put("rckServerUrl", TextNode.valueOf(url));
		extra.forEach((k, v) -> args.put(k, TextNode.valueOf(v)));
		return new JavaOperationProviderConfig(RCKSimulationOperation.class.getName(), args);
	}

	/** MDT에 접속하지 않는 오퍼레이션. */
	private static RCKSimulationOperation operation(JavaOperationProviderConfig config)
		throws ConfigurationInitializationException {
		return new RCKSimulationOperation(null, null, config) {
			@Override
			SimulationObserver createObserver() {
				return SimulationObserver.NONE;
			}
		};
	}

	private static Property property(String name, DataTypeDefXsd type, String value) {
		return new DefaultProperty.Builder().idShort(name).valueType(type).value(value).build();
	}

	private static Map<String,SubmodelElement> inputs(String layoutName) {
		return Map.of("LayoutName", property("LayoutName", DataTypeDefXsd.STRING, layoutName));
	}

	private static Map<String,SubmodelElement> outputs() {
		Map<String,SubmodelElement> outputs = new HashMap<>();
		outputs.put("Progress", property("Progress", DataTypeDefXsd.INT, null));
		outputs.put("Production", property("Production", DataTypeDefXsd.INT, null));
		outputs.put("FinalState", property("FinalState", DataTypeDefXsd.STRING, null));
		outputs.put("AverageUtilization", property("AverageUtilization", DataTypeDefXsd.STRING, null));
		outputs.put("SimulationVideoUrl", property("SimulationVideoUrl", DataTypeDefXsd.STRING, "old"));
		return outputs;
	}

	private static String value(Map<String,SubmodelElement> vars, String name) {
		return ((Property)vars.get(name)).getValue();
	}

	@Test(timeout = 15_000)
	public void completed_simulation_writes_outputs() throws Exception {
		String url = startServer(new FakeRckServer(false, false)
									.thenSend(RCKSimulationTest.STATUS, RCKSimulationTest.videoInfo(0)));
		Map<String,SubmodelElement> outputs = outputs();

		operation(config(url, Map.of())).invokeSync(inputs("layout"), Map.of(), outputs);

		assertEquals("100", value(outputs, "Progress"));
		assertEquals("7", value(outputs, "Production"));
		assertEquals("/Completed", value(outputs, "FinalState"));
		assertEquals(MDTSimulationOutputs.toUtilizationJson(Map.of("TP-75", 40f)), value(outputs, "AverageUtilization"));
		assertEquals("http://video/v.mp4", value(outputs, "SimulationVideoUrl"));
	}

	@Test(timeout = 15_000)
	public void max_duration_cancels_the_simulation_and_sends_stop() throws Exception {
		// 시뮬레이터가 응답하지 않으면 maxDuration 뒤에 취소되고, 연결을 닫기 전에 중지 명령이 간다.
		FakeRckServer server = new FakeRckServer(false, false);
		server.m_ignoreLayout = true;
		String url = startServer(server);
		RCKSimulationOperation op = operation(config(url, Map.of("maxDuration", "500ms")));

		assertThrows(TimeoutException.class, () -> op.invokeSync(inputs("layout"), Map.of(), outputs()));
		server.assertStopBeforeClose();
	}

	@Test
	public void empty_layout_name_is_rejected() throws Exception {
		RCKSimulationOperation op = operation(config("ws://127.0.0.1:1/rck", Map.of()));

		assertThrows(IllegalArgumentException.class, () -> op.invokeSync(inputs(" "), Map.of(), outputs()));
	}

	@Test
	public void invalid_arguments_are_rejected_at_configuration() {
		Map<String,JsonNode> noClient = new HashMap<>(config("ws://x", Map.of()).getArguments());
		noClient.remove("clientId");
		assertThrows(ConfigurationInitializationException.class,
					() -> operation(new JavaOperationProviderConfig("x", noClient)));
		assertThrows(ConfigurationInitializationException.class,
					() -> operation(config("ws://x", Map.of("pongTimeout", "abc"))));
		assertThrows(ConfigurationInitializationException.class,
					() -> operation(config("", Map.of())));
	}

	@Test
	public void duration_arguments_are_accepted() throws Exception {
		// 예외 없이 생성되어야 한다.
		operation(config("ws://x", Map.of("responseTimeout", "90s", "pingInterval", "30s",
										"pongTimeout", "5s", "maxDuration", "1h")));
	}
}
