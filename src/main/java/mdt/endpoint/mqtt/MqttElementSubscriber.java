package mdt.endpoint.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jetbrains.annotations.Nullable;

import utils.Preconditions;

import mdt.ElementLocation;
import mdt.ElementLocations;

/**
 * MQTT 엔드포인트의 구독 설정 하나({@code serviceEndpoints.mqtt.subscribers}의 원소).
 * <p>
 * 구독할 토픽, 값을 반영할 AAS 요소 위치, 반영 방식({@link DeliveryMode}), broker 구독 QoS,
 * payload 형식({@link PayloadFormat})으로 이루어진다. {@code topic}과 {@code element} 외에는 생략할 수 있다.
 * <pre>{@code
 * { "topic": "factory/robot01/temperature", "element": "RobotState:Temperature", "delivery": "latest" }
 * { "topic": "factory/robot01/status", "element": "RobotState:Status", "format": "text", "qos": 0 }
 * }</pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "topic", "element", "delivery", "qos", "format" })
public class MqttElementSubscriber {
	/** broker 구독 QoS의 기본값. */
	public static final int DEFAULT_QOS = 1;
	
	private final String m_topic;
	private final ElementLocation m_elementLoc;
	private final DeliveryMode m_delivery;
	private final int m_qos;
	private final PayloadFormat m_format;
	
	public MqttElementSubscriber(String topic, String elementLocExpr) {
		this(topic, elementLocExpr, null, null, null);
	}
	
	public MqttElementSubscriber(String topic, String elementLocExpr, @Nullable DeliveryMode delivery) {
		this(topic, elementLocExpr, delivery, null, null);
	}
	
	/**
	 * 구독 설정을 만든다.
	 *
	 * @param topic				구독할 토픽. MQTT 와일드카드를 쓸 수 있다.
	 * @param elementLocExpr	값을 반영할 요소 위치 표현식.
	 * @param delivery			반영 방식. {@code null}이면 {@link DeliveryMode#ALL}.
	 * @param qos				broker 구독 QoS(0, 1, 2). {@code null}이면 {@link #DEFAULT_QOS}.
	 * @param format			payload 형식. {@code null}이면 {@link PayloadFormat#JSON}.
	 * @throws IllegalArgumentException	{@code topic}이나 {@code element}가 없거나 {@code qos}가 범위를 벗어난 경우.
	 */
	@JsonCreator
	public MqttElementSubscriber(@JsonProperty("topic") String topic,
								@JsonProperty("element") String elementLocExpr,
								@JsonProperty("delivery") @Nullable DeliveryMode delivery,
								@JsonProperty("qos") @Nullable Integer qos,
								@JsonProperty("format") @Nullable PayloadFormat format) {
		Preconditions.checkNotNullArgument(topic, "topic is null");
		Preconditions.checkNotNullArgument(elementLocExpr, "element is null");
		Preconditions.checkArgument(qos == null || (qos >= 0 && qos <= 2),
									"invalid qos: %s (expected: 0, 1 or 2)", qos);
		
		m_topic = topic;
		m_elementLoc = ElementLocations.parseStringExpr(elementLocExpr);
		m_delivery = (delivery != null) ? delivery : DeliveryMode.ALL;
		m_qos = (qos != null) ? qos : DEFAULT_QOS;
		m_format = (format != null) ? format : PayloadFormat.JSON;
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
	
	/**
	 * broker 구독 QoS를 반환한다. 같은 토픽을 여러 곳에서 구독하면 broker 구독에는 요청된 QoS 중 가장 큰 값이 쓰인다.
	 *
	 * @return	QoS (0, 1, 2).
	 */
	@JsonProperty("qos")
	public int getQos() {
		return m_qos;
	}
	
	@JsonProperty("format")
	public PayloadFormat getFormat() {
		return m_format;
	}
	
	@Override
	public String toString() {
		// 메시지 흐름(토픽 → 요소)과 같은 방향으로 출력한다.
		// 기본값이 아닌 옵션만 덧붙인다.
		StringBuilder options = new StringBuilder();
		if ( m_delivery != DeliveryMode.ALL ) {
			options.append(", delivery=").append(m_delivery.toJsonValue());
		}
		if ( m_qos != DEFAULT_QOS ) {
			options.append(", qos=").append(m_qos);
		}
		if ( m_format != PayloadFormat.JSON ) {
			options.append(", format=").append(m_format.toJsonValue());
		}
		return String.format("%s -> %s%s", m_topic, m_elementLoc.toStringExpr(), options);
	}
}
