package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import java.util.List;

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
	
	@Test
	public void testQosAndFormatOptions() throws Exception {
		MqttEndpointConfig.MDTConfig conf = read("{ \"subscribers\": [ "
					+ "{ \"topic\": \"a\", \"element\": \"State:A\" }, "
					+ "{ \"topic\": \"b\", \"element\": \"State:B\", \"qos\": 0, \"format\": \"text\" }, "
					+ "{ \"topic\": \"c\", \"element\": \"State:C\", \"qos\": 2, \"format\": \"JSON\" } ] }");
		List<MqttElementSubscriber> subs = conf.getSubscribers();
		assertEquals(1, subs.get(0).getQos());								// 기본값
		assertEquals(PayloadFormat.JSON, subs.get(0).getFormat());			// 기본값
		assertEquals(0, subs.get(1).getQos());
		assertEquals(PayloadFormat.TEXT, subs.get(1).getFormat());
		assertEquals(2, subs.get(2).getQos());
		assertEquals(PayloadFormat.JSON, subs.get(2).getFormat());			// 대소문자 무시
		
		JsonNode json = MDTModelSerDe.getJsonMapper().valueToTree(conf);
		assertEquals(0, json.get("subscribers").get(1).get("qos").asInt());
		assertEquals("text", json.get("subscribers").get(1).get("format").asText());
		assertEquals("b -> State:B, qos=0, format=text", subs.get(1).toString());
	}

	@Test
	public void testInvalidQosAndFormatAreRejected() {
		for ( String option: new String[] { "\"qos\": 3", "\"qos\": -1", "\"format\": \"xml\"" } ) {
			assertThrows(option, Exception.class, () -> read("{ \"subscribers\": [ "
						+ "{ \"topic\": \"a\", \"element\": \"State:A\", " + option + " } ] }"));
		}
	}

	@Test
	public void testInvalidTopicIsRejectedWhenReading() {
		// MQTT-02: 잘못된 topic 필터는 broker 연결 전, 설정을 읽는 단계에서 위치와 함께 거부된다.
		for ( String topic: new String[] { "", "a/#/b", "a+" } ) {
			Exception e = assertThrows(topic, Exception.class, () -> read("{ \"subscribers\": [ "
						+ "{ \"topic\": \"ok\", \"element\": \"State:A\" }, "
						+ "{ \"topic\": \"" + topic + "\", \"element\": \"State:B\" } ] }"));
			assertTrue(e.getMessage(), e.getMessage().contains("invalid MQTT topic filter"));
			assertTrue("the message must point to the entry: " + e.getMessage(), e.getMessage().contains("[1]"));
		}
	}

	@Test
	public void testNullSubscriberIsRejected() {
		Exception e = assertThrows(Exception.class, () -> read("{ \"subscribers\": [ null ] }"));
		assertTrue(e.getMessage(), e.getMessage().contains("subscribers[0] is null"));
	}

	@Test
	public void testInFlightOptions() throws Exception {
		MqttEndpointConfig.MDTConfig conf = read("{ \"subscribers\": [ "
					+ "{ \"topic\": \"a\", \"element\": \"State:A\" }, "
					+ "{ \"topic\": \"b\", \"element\": \"State:B\", \"maxInFlightMessages\": 50, \"overflowPolicy\": \"DROP\" } ] }");
		List<MqttElementSubscriber> subs = conf.getSubscribers();
		assertEquals(1000, subs.get(0).getMaxInFlightMessages());
		assertEquals(OverflowPolicy.WAIT, subs.get(0).getOverflowPolicy());
		assertEquals(50, subs.get(1).getMaxInFlightMessages());
		assertEquals(OverflowPolicy.DROP, subs.get(1).getOverflowPolicy());
		assertEquals("b -> State:B, maxInFlightMessages=50, overflowPolicy=drop", subs.get(1).toString());
		
		for ( String option: new String[] { "\"maxInFlightMessages\": 0", "\"overflowPolicy\": \"block\"" } ) {
			assertThrows(option, Exception.class, () -> read("{ \"subscribers\": [ "
						+ "{ \"topic\": \"a\", \"element\": \"State:A\", " + option + " } ] }"));
		}
	}

	private static MqttEndpointConfig.MDTConfig read(String mqttJson) throws Exception {
		ServiceEndpointConfigs configs = MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class)
															.readValue("{ \"mqtt\": " + mqttJson + " }");
		return configs.getMqtt();
	}
}
