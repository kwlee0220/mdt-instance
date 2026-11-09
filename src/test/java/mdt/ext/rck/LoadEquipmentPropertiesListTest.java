package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.junit.After;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import mdt.assetconnection.operation.JavaOperationProviderConfig;


/**
 * {@link LoadEquipmentPropertiesList}의 인자 처리, 출력 기록, 실패 처리를 가짜 RCK 서버로 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class LoadEquipmentPropertiesListTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String OUTPUT = "EquipmentPropertiesList";
	// 시뮬레이터에 접속하면 실패하는 주소. 접속 전에 끝나는 시험에 쓴다.
	private static final String UNUSED_URL = "ws://localhost:1/rck";

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

	private static LoadEquipmentPropertiesList operation(String url, Map<String,String> extra)
		throws ConfigurationInitializationException {
		Map<String,JsonNode> args = new HashMap<>();
		args.put("clientId", TextNode.valueOf("test-client"));
		args.put("processName", TextNode.valueOf("press"));
		args.put("rckServerUrl", TextNode.valueOf(url));
		extra.forEach((k, v) -> args.put(k, (v != null) ? TextNode.valueOf(v) : null));
		args.values().removeIf(v -> v == null);
		return new LoadEquipmentPropertiesList(null, null,
								new JavaOperationProviderConfig(LoadEquipmentPropertiesList.class.getName(), args));
	}

	private static Property property(String name, DataTypeDefXsd type, String value) {
		return new DefaultProperty.Builder().idShort(name).valueType(type).value(value).build();
	}

	private static OperationVariable[] vars(SubmodelElement... elements) {
		OperationVariable[] vars = new OperationVariable[elements.length];
		for ( int i = 0; i < elements.length; ++i ) {
			vars[i] = new DefaultOperationVariable.Builder().value(elements[i]).build();
		}
		return vars;
	}

	private static OperationVariable[] layout(String name) {
		return vars(property("LayoutName", DataTypeDefXsd.STRING, name));
	}

	private static Property output() {
		return property(OUTPUT, DataTypeDefXsd.STRING, null);
	}

	private static void invoke(LoadEquipmentPropertiesList op, OperationVariable[] inputs,
								OperationVariable[] outputs) throws Exception {
		op.invokeSync(inputs, new OperationVariable[0], outputs);
	}

	@Test(timeout = 15_000)
	public void writes_simulator_message_as_json_string_without_starting() throws Exception {
		JsonNode message = MAPPER.readTree(new File("misc/facility_press.json")).path("message");
		FakeRckServer server = new FakeRckServer(false, false);
		server.m_equipmentMessage = message.toString();
		Property output = output();

		invoke(operation(startServer(server), Map.of()), layout("layout"), vars(output));

		// 문자열 값을 다시 읽어 키 순서까지 비교한다.
		assertEquals(message.toString(), MAPPER.readTree(output.getValue()).toString());
		assertTrue("connection was not closed", server.m_closing.await(5, TimeUnit.SECONDS));
		assertFalse("start command must not be sent", server.m_events.contains("command"));
		assertFalse("stop command must not be sent", server.m_events.contains("stop"));
	}

	@Test
	public void missing_or_blank_config_argument_is_rejected() {
		Map<String,String> noClient = new HashMap<>();
		noClient.put("clientId", null);
		assertThrows(ConfigurationInitializationException.class, () -> operation(UNUSED_URL, noClient));
		assertThrows(ConfigurationInitializationException.class, () -> operation(UNUSED_URL, Map.of("processName", " ")));
		assertThrows(ConfigurationInitializationException.class, () -> operation(" ", Map.of()));
	}

	@Test
	public void invalid_response_timeout_is_rejected() {
		assertThrows(ConfigurationInitializationException.class,
					() -> operation(UNUSED_URL, Map.of("responseTimeout", "abc")));
		assertThrows(ConfigurationInitializationException.class,
					() -> operation(UNUSED_URL, Map.of("responseTimeout", "0s")));
	}

	@Test
	public void invalid_variables_are_rejected_before_connecting() throws Exception {
		LoadEquipmentPropertiesList op = operation(UNUSED_URL, Map.of());

		assertThrows(IllegalArgumentException.class, () -> invoke(op, vars(), vars(output())));
		assertThrows(IllegalArgumentException.class, () -> invoke(op, layout(" "), vars(output())));
		assertThrows(IllegalArgumentException.class, () -> invoke(op, layout(null), vars(output())));
		assertThrows(IllegalArgumentException.class, () -> invoke(op, layout("layout"), vars()));
		assertThrows(IllegalArgumentException.class,
					() -> invoke(op, layout("layout"), vars(property(OUTPUT, DataTypeDefXsd.INT, null))));
	}

	@Test(timeout = 15_000)
	public void malformed_message_fails_without_writing_output() throws Exception {
		FakeRckServer server = new FakeRckServer(false, false);
		server.m_equipmentMessage = "[]";
		Property output = output();
		LoadEquipmentPropertiesList op = operation(startServer(server), Map.of());

		assertThrows(ExecutionException.class, () -> invoke(op, layout("layout"), vars(output)));
		assertNull(output.getValue());
	}

	@Test(timeout = 15_000)
	public void response_timeout_argument_is_applied() throws Exception {
		FakeRckServer server = new FakeRckServer(false, false);
		server.m_ignoreLayout = true;
		LoadEquipmentPropertiesList op = operation(startServer(server), Map.of("responseTimeout", "200ms"));

		assertThrows(ExecutionException.class, () -> invoke(op, layout("layout"), vars(output())));
	}

	@Test(timeout = 15_000)
	public void concurrent_call_is_rejected_and_next_call_is_accepted_after_interrupt() throws Exception {
		FakeRckServer server = new FakeRckServer(false, false);
		server.m_ignoreLayout = true;
		LoadEquipmentPropertiesList op = operation(startServer(server), Map.of());

		List<Throwable> thrown = Collections.synchronizedList(new ArrayList<>());
		Thread caller = new Thread(() -> {
			try {
				invoke(op, layout("layout"), vars(output()));
			}
			catch ( Throwable e ) {
				thrown.add(e);
			}
		});
		caller.start();
		long due = System.currentTimeMillis() + 5000;
		while ( !server.m_events.contains("layout") && System.currentTimeMillis() < due ) {
			Thread.sleep(20);
		}

		assertThrows(IllegalStateException.class, () -> invoke(op, layout("layout"), vars(output())));

		caller.interrupt();
		caller.join(5000);
		assertEquals(1, thrown.size());
		assertTrue("" + thrown.get(0), thrown.get(0) instanceof InterruptedException);
		assertFalse("stop command must not be sent", server.m_events.contains("stop"));

		// 끝난 뒤에는 다시 호출할 수 있다. 접속할 서버가 없으므로 실행 중 거부가 아니라 접속 실패로 끝난다.
		m_server.close();
		assertThrows(ExecutionException.class, () -> invoke(op, layout("layout"), vars(output())));
	}
}
