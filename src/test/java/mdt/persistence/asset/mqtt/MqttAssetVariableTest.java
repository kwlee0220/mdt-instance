package mdt.persistence.asset.mqtt;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import mdt.MDTGlobalConfigurations;
import mdt.client.support.MqttService;
import mdt.persistence.asset.AssetVariableException;
import mdt.persistence.asset.mqtt.MqttAssetVariableConfig.DisconnectedPolicy;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SuppressWarnings("resource")
public class MqttAssetVariableTest {
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@Before
	public void setUp() {
		// 전역 설정 파일이 지정되지 않은 상태: 전역 설정을 읽으면 IllegalStateException이 발생한다.
		MDTGlobalConfigurations.setGlobalConfigFile(null);
	}

	@After
	public void tearDown() {
		MDTGlobalConfigurations.setGlobalConfigFile(null);
	}

	@Test
	public void testToStringDoesNotLoadGlobalConfig() throws Exception {
		MqttAssetVariableConfig config = load("""
			{ "element": "Telemetry:Measurements", "mqttBrokerConfigName": "factory",
			  "topic": "machines/m01/measurements" }
			""");

		String str = config.toString();
		assertTrue(str, str.contains("mqttBrokers.factory"));
		assertTrue(str, str.contains("machines/m01/measurements"));

		// 초기화 전의 AssetVariable도 toString()이 실패하지 않아야 한다.
		try ( MqttAssetVariable var = new MqttAssetVariable(config) ) {
			String varStr = var.toString();
			assertTrue(varStr, varStr.contains("machines/m01/measurements"));
		}
	}

	@Test
	public void testToStringWithInlineBroker() throws Exception {
		MqttAssetVariableConfig config = load("""
			{ "element": "Telemetry:Measurements", "topic": "t",
			  "mqttBrokerConfig": { "brokerUrl": "tcp://broker.example:1883" } }
			""");

		String str = config.toString();
		assertTrue(str, str.contains("tcp://broker.example:1883"));
		assertEquals("tcp://broker.example:1883", config.getMqttBrokerConfig().getBrokerUrl());
	}

	@Test
	public void testGlobalBrokerConfigNotFound() throws Exception {
		MDTGlobalConfigurations.setGlobalConfigFile(new File("no-such-global-config.json"));
		MqttAssetVariableConfig config = load("""
			{ "element": "Telemetry:Measurements", "topic": "t" }
			""");

		assertThrows(IllegalStateException.class, config::getMqttBrokerConfig);
		// broker 설정을 해석할 수 없어도 toString()은 정상 동작해야 한다.
		assertTrue(config.toString().contains("mqttBrokers.default"));
	}

	@Test
	public void testCloseWithoutInitialize() throws Exception {
		MqttAssetVariableConfig config = load("""
			{ "element": "Telemetry:Measurements", "topic": "t" }
			""");

		MqttAssetVariable var = new MqttAssetVariable(config);
		var.close();
		var.close();
	}

	@Test
	public void testDisconnectedPolicyParsing() throws Exception {
		MqttAssetVariableConfig dflt = load("""
			{ "element": "Telemetry:Measurements", "topic": "t" }
			""");
		assertEquals(DisconnectedPolicy.SKIP, dflt.getWhenDisconnected());

		MqttAssetVariableConfig fail = load("""
			{ "element": "Telemetry:Measurements", "topic": "t", "whenDisconnected": "FAIL" }
			""");
		assertEquals(DisconnectedPolicy.FAIL, fail.getWhenDisconnected());

		assertThrows(IOException.class, () -> load("""
			{ "element": "Telemetry:Measurements", "topic": "t", "whenDisconnected": "retry" }
			"""));
	}

	@Test
	public void testDisconnectedPolicySerialization() throws Exception {
		MqttAssetVariableConfig config = load("""
			{ "element": "Telemetry:Measurements", "topic": "t", "whenDisconnected": "fail" }
			""");

		StringWriter writer = new StringWriter();
		try ( JsonGenerator gen = MAPPER.getFactory().createGenerator(writer) ) {
			gen.writeStartObject();
			config.serializeFields(gen);
			gen.writeEndObject();
		}
		JsonNode written = MAPPER.readTree(writer.toString());
		assertEquals("fail", written.get("whenDisconnected").asText());
		assertEquals(DisconnectedPolicy.FAIL, MqttAssetVariableConfig.deserializeFields(written).getWhenDisconnected());
	}

	@Test
	public void testSkipWhenNotConnected() throws Exception {
		MqttService mqtt = mock(MqttService.class);
		when(mqtt.isConnected()).thenReturn(false);

		MqttAssetVariable var = newVariable("skip", mqtt);
		var.update(newProperty("10"));
		var.update(newProperty("11"));
		verify(mqtt, never()).publish(anyString(), anyString());
	}

	@Test
	public void testFailWhenNotConnected() throws Exception {
		MqttService mqtt = mock(MqttService.class);
		when(mqtt.isConnected()).thenReturn(false);

		MqttAssetVariable var = newVariable("fail", mqtt);
		assertThrows(AssetVariableException.class, () -> var.update(newProperty("10")));
		verify(mqtt, never()).publish(anyString(), anyString());
	}

	@Test
	public void testSkipWhenConnectionLostWhilePublishing() throws Exception {
		MqttService mqtt = mock(MqttService.class);
		when(mqtt.isConnected()).thenReturn(true);
		doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED))
			.when(mqtt).publish(anyString(), anyString());

		MqttAssetVariable var = newVariable("skip", mqtt);
		var.update(newProperty("10"));
	}

	@Test
	public void testOtherPublishErrorFailsRegardlessOfPolicy() throws Exception {
		MqttService mqtt = mock(MqttService.class);
		when(mqtt.isConnected()).thenReturn(true);
		doThrow(new MqttException(MqttException.REASON_CODE_NOT_AUTHORIZED))
			.when(mqtt).publish(anyString(), anyString());

		MqttAssetVariable var = newVariable("skip", mqtt);
		assertThrows(AssetVariableException.class, () -> var.update(newProperty("10")));
	}

	@Test
	public void testPublishWhenConnected() throws Exception {
		MqttService mqtt = mock(MqttService.class);
		when(mqtt.isConnected()).thenReturn(true);

		MqttAssetVariable var = newVariable("fail", mqtt);
		var.update(newProperty("10"));
		verify(mqtt).publish(eq("t"), anyString());
	}

	// setMqttService()로 지정한 서비스는 MqttServicePool에서 획득한 것이 아니므로,
	// 이렇게 만든 AssetVariable은 close()할 필요가 없다.
	private static MqttAssetVariable newVariable(String policy, MqttService mqtt) throws Exception {
		MqttAssetVariableConfig config = load(String.format("""
			{ "element": "Telemetry:Measurements", "topic": "t", "whenDisconnected": "%s" }
			""", policy));
		MqttAssetVariable var = new MqttAssetVariable(config);
		var.setMqttService(mqtt);
		return var;
	}

	private static Property newProperty(String value) {
		return new DefaultProperty.Builder()
								.idShort("Measurements")
								.valueType(DataTypeDefXsd.INT)
								.value(value)
								.build();
	}

	private static MqttAssetVariableConfig load(String json) throws Exception {
		JsonNode jnode = MAPPER.readTree(json);
		return MqttAssetVariableConfig.deserializeFields(jnode);
	}
}
