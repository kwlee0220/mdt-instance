package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.client.support.MqttBrokerConfig;
import mdt.config.ServiceEndpointConfigs;
import mdt.model.MDTModelSerDe;


/**
 * {@code serviceEndpoints.mqtt.mqttBrokerConfig}가 전역 설정 이름과 인라인 broker 설정을 모두 받는지 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttEndpointConfigTest {
	private static final String SUBSCRIBERS
							= "\"subscribers\": [ { \"topic\": \"factory/robot01/temperature\", \"element\": \"State:Temperature\" } ]";

	@Test
	public void testBrokerByName() throws Exception {
		MqttEndpointConfig.MDTConfig conf = read("{ \"mqttBrokerConfig\": \"factory\", " + SUBSCRIBERS + " }");
		assertEquals("factory", conf.getMqttBrokerConfigRef().name());
		assertNull(conf.getMqttBrokerConfigRef().inline());
		assertEquals(1, conf.getSubscribers().size());
	}

	@Test
	public void testInlineBroker() throws Exception {
		MqttEndpointConfig.MDTConfig conf = read("{ \"mqttBrokerConfig\": { \"brokerUrl\": \"tcp://broker.local:1883\", "
												+ "\"reconnectInterval\": \"3s\" }, " + SUBSCRIBERS + " }");
		assertNull(conf.getMqttBrokerConfigRef().name());

		// 인라인 설정은 전역 설정을 조회하지 않고 그대로 쓴다.
		MqttBrokerConfig broker = new MqttEndpointConfig(conf).getMqttBrokerConfig();
		assertEquals("tcp://broker.local:1883", broker.getBrokerUrl());
		assertEquals("3s", broker.getReconnectInterval());
	}

	@Test
	public void testDefaultBroker() throws Exception {
		assertEquals("default", read("{ " + SUBSCRIBERS + " }").getMqttBrokerConfigRef().name());
		assertEquals("default", read("{ \"mqttBrokerConfig\": null, " + SUBSCRIBERS + " }")
									.getMqttBrokerConfigRef().name());
	}

	@Test
	public void testInvalidBrokerIsRejectedWhenReading() {
		for ( String value: new String[] { "3", "[\"factory\"]", "true" } ) {
			Exception e = assertThrows(value, Exception.class,
										() -> read("{ \"mqttBrokerConfig\": " + value + ", " + SUBSCRIBERS + " }"));
			assertTrue(e.getMessage(), e.getMessage().contains("mqttBrokerConfig"));
		}
	}

	@Test
	public void testRoundTripKeepsForm() throws Exception {
		MqttEndpointConfig.MDTConfig byName = read("{ \"mqttBrokerConfig\": \"factory\", " + SUBSCRIBERS + " }");
		JsonNode nameJson = MDTModelSerDe.getJsonMapper().valueToTree(byName);
		assertEquals("factory", nameJson.get("mqttBrokerConfig").asText());

		MqttEndpointConfig.MDTConfig inline = read("{ \"mqttBrokerConfig\": { \"brokerUrl\": \"tcp://broker.local:1883\" }, "
													+ SUBSCRIBERS + " }");
		JsonNode inlineJson = MDTModelSerDe.getJsonMapper().valueToTree(inline);
		assertTrue(inlineJson.get("mqttBrokerConfig").isObject());

		MqttEndpointConfig.MDTConfig reread = MDTModelSerDe.readValue(inlineJson, MqttEndpointConfig.MDTConfig.class);
		assertEquals("tcp://broker.local:1883", reread.getMqttBrokerConfigRef().inline().getBrokerUrl());
	}

	@Test
	public void testToStringDoesNotReadGlobalConfig() throws Exception {
		// 전역 설정에 없는 이름이어도 toString()은 조회하지 않으므로 실패하지 않는다.
		MqttEndpointConfig.MDTConfig conf = read("{ \"mqttBrokerConfig\": \"no-such-broker\", " + SUBSCRIBERS + " }");
		String str = new MqttEndpointConfig(conf).toString();
		assertTrue(str, str.contains("mqttBrokers.no-such-broker"));
		assertTrue(str, str.contains("factory/robot01/temperature"));

		MqttEndpointConfig.MDTConfig inline = read("{ \"mqttBrokerConfig\": { \"brokerUrl\": \"tcp://broker.local:1883\" }, "
													+ SUBSCRIBERS + " }");
		assertTrue(inline.toString(), inline.toString().contains("tcp://broker.local:1883"));
	}

	@Test
	public void testDeliveryOption() throws Exception {
		MqttEndpointConfig.MDTConfig conf = read("{ \"subscribers\": [ "
					+ "{ \"topic\": \"a\", \"element\": \"State:A\" }, "
					+ "{ \"topic\": \"b\", \"element\": \"State:B\", \"delivery\": \"latest\" }, "
					+ "{ \"topic\": \"c\", \"element\": \"State:C\", \"delivery\": \"ALL\" } ] }");
		assertEquals(DeliveryMode.ALL, conf.getSubscribers().get(0).getDelivery());		// 생략 시 기본값
		assertEquals(DeliveryMode.LATEST, conf.getSubscribers().get(1).getDelivery());
		assertEquals(DeliveryMode.ALL, conf.getSubscribers().get(2).getDelivery());		// 대소문자 무시
		
		JsonNode json = MDTModelSerDe.getJsonMapper().valueToTree(conf);
		assertEquals("latest", json.get("subscribers").get(1).get("delivery").asText());
	}

	@Test
	public void testSubscriberToStringFollowsMessageFlow() {
		// 메시지 흐름과 같은 방향(토픽 → 요소)으로 출력하고, 기본값이 아닌 반영 방식만 덧붙인다.
		assertEquals("t/a -> State:A", new MqttElementSubscriber("t/a", "State:A").toString());
		assertEquals("t/b -> State:B, delivery=latest",
					new MqttElementSubscriber("t/b", "State:B", DeliveryMode.LATEST).toString());
	}

	@Test
	public void testInvalidDeliveryIsRejected() {
		Exception e = assertThrows(Exception.class, () -> read("{ \"subscribers\": [ "
					+ "{ \"topic\": \"a\", \"element\": \"State:A\", \"delivery\": \"newest\" } ] }"));
		assertTrue(e.getMessage(), e.getMessage().contains("newest"));
	}
	
	private static MqttEndpointConfig.MDTConfig read(String mqttJson) throws Exception {
		ServiceEndpointConfigs configs = MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class)
															.readValue("{ \"mqtt\": " + mqttJson + " }");
		return configs.getMqtt();
	}
}
