package mdt.endpoint.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import utils.Preconditions;

import mdt.ElementLocation;
import mdt.ElementLocations;

/**
 * MQTT 엔드포인트의 구독 설정 하나({@code serviceEndpoints.mqtt.subscribers}의 원소).
 * <p>
 * 구독할 토픽, 값을 반영할 AAS 요소 위치, 반영 방식({@link DeliveryMode})으로 이루어진다.
 * <pre>{@code
 * { "topic": "factory/robot01/temperature", "element": "RobotState:Temperature", "delivery": "latest" }
 * }</pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "topic", "element", "delivery" })
public class MqttElementSubscriber {
	private final String m_topic;
	private final ElementLocation m_elementLoc;
	private final DeliveryMode m_delivery;
	
	public MqttElementSubscriber(String topic, String elementLocExpr) {
		this(topic, elementLocExpr, null);
	}
	
	/**
	 * 구독 설정을 만든다.
	 *
	 * @param topic				구독할 토픽. MQTT 와일드카드를 쓸 수 있다.
	 * @param elementLocExpr	값을 반영할 요소 위치 표현식.
	 * @param delivery			반영 방식. {@code null}이면 {@link DeliveryMode#ALL}.
	 */
	@JsonCreator
	public MqttElementSubscriber(@JsonProperty("topic") String topic,
								@JsonProperty("element") String elementLocExpr,
								@JsonProperty("delivery") DeliveryMode delivery) {
		Preconditions.checkNotNullArgument(topic, "topic is null");
		Preconditions.checkNotNullArgument(elementLocExpr, "element is null");
		
		m_topic = topic;
		m_elementLoc = ElementLocations.parseStringExpr(elementLocExpr);
		m_delivery = (delivery != null) ? delivery : DeliveryMode.ALL;
	}
	
	public String getTopic() {
		return m_topic;
	}
	
	public ElementLocation getElementLocation() {
		return m_elementLoc;
	}
	
	@JsonProperty("element")
	public String getElementLocationExpr() {
		return m_elementLoc.toStringExpr();
	}
	
	@JsonProperty("delivery")
	public DeliveryMode getDelivery() {
		return m_delivery;
	}
	
	@Override
	public String toString() {
		// 메시지 흐름(토픽 → 요소)과 같은 방향으로 출력한다.
		String delivery = (m_delivery != DeliveryMode.ALL) ? ", delivery=" + m_delivery.toJsonValue() : "";
		return String.format("%s -> %s%s", m_topic, m_elementLoc.toStringExpr(), delivery);
	}
}
