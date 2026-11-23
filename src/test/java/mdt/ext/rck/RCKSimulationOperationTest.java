package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultKey;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultReference;
import org.junit.After;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.model.sm.ref.DefaultSubmodelReference;


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
		outputs.put("EquipmentUtilizationList", property("EquipmentUtilizationList", DataTypeDefXsd.STRING, null));
		outputs.put("SimulationVideoUrl", property("SimulationVideoUrl", DataTypeDefXsd.STRING, "old"));
		return outputs;
	}

	private static String value(Map<String,SubmodelElement> vars, String name) {
		return ((Property)vars.get(name)).getValue();
	}

	@Test(timeout = 15_000)
	public void completed_simulation_writes_outputs() throws Exception {
		String url = startServer(new FakeRckServer(false, false)
									.thenSend(RCKSimulationTest.STATUS, RCKSimulationTest.videoInfo()));
		Map<String,SubmodelElement> outputs = outputs();

		operation(config(url, Map.of())).invokeSync(inputs("layout"), Map.of(), outputs);

		assertEquals("100", value(outputs, "Progress"));
		assertEquals("7", value(outputs, "Production"));
		assertEquals(MDTSimulationOutputReporter.toUtilizationJson(
						Map.of(new RCKEquipment("Press", "TP-75 #1", 1), 40f)), value(outputs, "EquipmentUtilizationList"));
		assertEquals("http://video/v.mp4", value(outputs, "SimulationVideoUrl"));
	}

	@Test
	public void missing_or_empty_equipment_properties_list_means_no_overrides() {
		for ( String json: new String[] { null, "", "  ", "{}" } ) {
			assertEquals("" + json, "{}", RCKSimulationOperation.parseEquipmentPropertiesList(json).toString());
		}
	}

	@Test
	public void malformed_equipment_properties_list_is_rejected() {
		for ( String json: new String[] { "not json", "[]", "{\"Press\":{\"Name\":\"p\"}}" } ) {
			assertThrows(json, IllegalArgumentException.class,
						() -> RCKSimulationOperation.parseEquipmentPropertiesList(json));
		}
	}

	@Test(timeout = 15_000)
	public void equipment_properties_list_input_overrides_start_command() throws Exception {
		FakeRckServer server = new FakeRckServer(false, false)
									.thenSend(RCKSimulationTest.STATUS, RCKSimulationTest.videoInfo());
		server.m_equipmentMessage = "{\"Press\":{\"Name\":\"p\",\"Index\":1,\"Time\":2.5,\"ClampTime\":1}}";
		String url = startServer(server);
		Map<String,SubmodelElement> inputs = new HashMap<>(inputs("layout"));
		inputs.put(RCKSimulationOperation.VAR_EQUIPMENT_PROPERTIES_LIST,
					property(RCKSimulationOperation.VAR_EQUIPMENT_PROPERTIES_LIST, DataTypeDefXsd.STRING,
							"{\"Press\":{\"Name\":\"p\",\"Index\":1,\"Time\":40}}"));

		operation(config(url, Map.of())).invokeSync(inputs, Map.of(), outputs());

		assertEquals("{\"Press\":{\"Name\":\"p\",\"Index\":1,\"Time\":40,\"ClampTime\":1}}",
					server.m_startCommand.get("message").toString());
	}

	@Test(timeout = 15_000)
	public void malformed_equipment_properties_list_input_fails_before_connecting() throws Exception {
		FakeRckServer server = new FakeRckServer(false, false);
		String url = startServer(server);
		Map<String,SubmodelElement> inputs = new HashMap<>(inputs("layout"));
		inputs.put(RCKSimulationOperation.VAR_EQUIPMENT_PROPERTIES_LIST,
					property(RCKSimulationOperation.VAR_EQUIPMENT_PROPERTIES_LIST, DataTypeDefXsd.STRING, "[]"));
		RCKSimulationOperation op = operation(config(url, Map.of()));

		assertThrows(IllegalArgumentException.class, () -> op.invokeSync(inputs, Map.of(), outputs()));
		assertEquals("must not connect", 0, m_server.getRequestCount());
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

	@Test(timeout = 15_000)
	public void call_while_running_is_rejected_and_accepted_after_finish() throws Exception {
		// 실행 중인 시뮬레이션이 있으면 새 호출은 시작하지 않고 거부한다. 끝나면 다음 호출을 받는다.
		FakeRckServer first = new FakeRckServer(false, false);
		first.m_ignoreLayout = true;
		String url = startServer(first);
		RCKSimulationOperation op = operation(config(url, Map.of("maxDuration", "1500ms")));
		CompletableFuture<Throwable> failure = new CompletableFuture<>();

		Thread caller = new Thread(() -> {
			try {
				op.invokeSync(inputs("layout"), Map.of(), outputs());
				failure.complete(null);
			}
			catch ( Throwable e ) {
				failure.complete(e);
			}
		});
		caller.start();
		Thread.sleep(300);
		assertThrows(IllegalStateException.class, () -> op.invokeSync(inputs("layout"), Map.of(), outputs()));

		assertTrue("" + failure.get(5, TimeUnit.SECONDS), failure.get() instanceof TimeoutException);
		m_server.enqueue(new MockResponse().withWebSocketUpgrade(
							new FakeRckServer(false, false).thenSend(RCKSimulationTest.STATUS,
																	RCKSimulationTest.videoInfo())));
		Map<String,SubmodelElement> outputs = outputs();
		// 결과 콜백이 불렸으면 다음 호출을 받는다.
		op.invokeSync(inputs("layout"), Map.of(), outputs);
		assertEquals("100", value(outputs, "Progress"));
	}

	@Test
	public void progress_target_is_the_submodel_of_the_operation_reference() {
		Reference opRef = new DefaultReference.Builder()
								.type(ReferenceTypes.MODEL_REFERENCE)
								.keys(new DefaultKey.Builder().type(KeyTypes.SUBMODEL).value("urn:sm:press-sim").build())
								.keys(new DefaultKey.Builder().type(KeyTypes.OPERATION).value("Run").build())
								.build();
		assertEquals(DefaultSubmodelReference.ofId("urn:sm:press-sim").toStringExpr(),
					RCKSimulationOperation.toOperationSubmodel(opRef, () -> "inst").toStringExpr());
		// 오퍼레이션 reference가 없으면 인스턴스의 기본 Submodel, 인스턴스도 모르면 반영하지 않는다.
		assertEquals(DefaultSubmodelReference.ofIdShort("inst", RCKSimulationOperation.DEFAULT_SUBMODEL_ID_SHORT)
											.toStringExpr(),
					RCKSimulationOperation.toOperationSubmodel(null, () -> "inst").toStringExpr());
		assertNull(RCKSimulationOperation.toOperationSubmodel(null, () -> null));
	}

	@Test(timeout = 15_000)
	public void omitted_optional_output_is_skipped() throws Exception {
		// 모델에 SimulationVideoUrl 출력 변수가 없어도 나머지 출력을 기록하고 성공한다.
		String url = startServer(new FakeRckServer(false, false)
									.thenSend(RCKSimulationTest.STATUS, RCKSimulationTest.videoInfo()));
		Map<String,SubmodelElement> outputs = outputs();
		outputs.remove("SimulationVideoUrl");

		operation(config(url, Map.of())).invokeSync(inputs("layout"), Map.of(), outputs);

		assertEquals("100", value(outputs, "Progress"));
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
		assertThrows(ConfigurationInitializationException.class,
					() -> operation(config("ws://x", Map.of("productionPath", "Line..Count"))));
	}

	@Test
	public void duration_arguments_are_accepted() throws Exception {
		// 예외 없이 생성되어야 한다.
		operation(config("ws://x", Map.of("responseTimeout", "90s", "pingInterval", "30s",
										"pongTimeout", "5s", "maxDuration", "1h")));
	}
}
