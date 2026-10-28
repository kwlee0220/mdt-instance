package mdt.endpoint.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jetbrains.annotations.Nullable;

import utils.Preconditions;

import mdt.ElementLocation;
import mdt.ElementLocations;
import mdt.client.support.MqttService;

/**
 * MQTT 엔드포인트의 구독 설정 하나({@code serviceEndpoints.mqtt.subscribers}의 원소).
 * <p>
 * 구독할 토픽, 값을 반영할 AAS 요소 위치, 반영 방식({@link DeliveryMode}), broker 구독 QoS,
 * payload 형식({@link PayloadFormat}), 처리 중 메시지 상한과 포화 정책({@link OverflowPolicy})으로 이루어진다.
 * {@code topic}과 {@code element} 외에는 생략할 수 있다. {@code topic}은 MQTT topic 필터 규칙에 맞아야 한다.
 * <pre>{@code
 * { "topic": "factory/robot01/temperature", "element": "RobotState:Temperature", "delivery": "latest" }
 * { "topic": "factory/robot01/status", "element": "RobotState:Status", "format": "text", "qos": 0 }
 * }</pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "topic", "element", "delivery", "qos", "format", "maxInFlightMessages", "overflowPolicy" })
public class MqttElementSubscriber {
	/** broker 구독 QoS의 기본값. */
	public static final int DEFAULT_QOS = 1;
	/** {@link DeliveryMode#ALL}에서 처리 중·대기 중인 메시지 수 상한의 기본값. */
	public static final int DEFAULT_MAX_IN_FLIGHT_MESSAGES = 1000;
	
	private final String m_topic;
	private final ElementLocation m_elementLoc;
	private final DeliveryMode m_delivery;
	private final int m_qos;
	private final PayloadFormat m_format;
	private final int m_maxInFlightMessages;
	private final OverflowPolicy m_overflowPolicy;
	
	public MqttElementSubscriber(String topic, String elementLocExpr) {
		this(topic, elementLocExpr, null, null, null, null, null);
	}
	
	public MqttElementSubscriber(String topic, String elementLocExpr, @Nullable DeliveryMode delivery) {
		this(topic, elementLocExpr, delivery, null, null, null, null);
	}
	
	public MqttElementSubscriber(String topic, String elementLocExpr, @Nullable DeliveryMode delivery,
								@Nullable Integer qos, @Nullable PayloadFormat format) {
		this(topic, elementLocExpr, delivery, qos, format, null, null);
	}
	
	/**
	 * 구독 설정을 만든다.
	 *
	 * @param topic				구독할 토픽. MQTT 와일드카드를 쓸 수 있다.
	 * @param elementLocExpr	값을 반영할 요소 위치 표현식.
	 * @param delivery			반영 방식. {@code null}이면 {@link DeliveryMode#ALL}.
	 * @param qos				broker 구독 QoS(0, 1, 2). {@code null}이면 {@link #DEFAULT_QOS}.
	 * @param format			payload 형식. {@code null}이면 {@link PayloadFormat#JSON}.
	 * @param maxInFlightMessages	{@link DeliveryMode#ALL}에서 처리 중·대기 중인 메시지 수의 상한(양수).
	 * 							{@code null}이면 {@link #DEFAULT_MAX_IN_FLIGHT_MESSAGES}.
	 * @param overflowPolicy	상한에 이르렀을 때의 동작. {@code null}이면 {@link OverflowPolicy#WAIT}.
	 * @throws IllegalArgumentException	{@code topic}이나 {@code element}가 없거나, {@code topic}이 MQTT topic 필터
	 * 							규칙에 맞지 않거나, {@code qos}·{@code maxInFlightMessages}가 범위를 벗어난 경우.
	 */
	@JsonCreator
	public MqttElementSubscriber(@JsonProperty("topic") String topic,
								@JsonProperty("element") String elementLocExpr,
								@JsonProperty("delivery") @Nullable DeliveryMode delivery,
								@JsonProperty("qos") @Nullable Integer qos,
								@JsonProperty("format") @Nullable PayloadFormat format,
								@JsonProperty("maxInFlightMessages") @Nullable Integer maxInFlightMessages,
								@JsonProperty("overflowPolicy") @Nullable OverflowPolicy overflowPolicy) {
		Preconditions.checkNotNullArgument(topic, "topic is null");
		Preconditions.checkNotNullArgument(elementLocExpr, "element is null");
		// 잘못된 topic을 broker 연결 전에, 설정을 읽는 단계에서 거부한다.
		MqttService.validateTopicFilter(topic);
		Preconditions.checkArgument(qos == null || (qos >= 0 && qos <= 2),
									"invalid qos: %s (expected: 0, 1 or 2)", qos);
		Preconditions.checkArgument(maxInFlightMessages == null || maxInFlightMessages > 0,
									"invalid maxInFlightMessages: %s (expected: a positive number)", maxInFlightMessages);
		
		m_topic = topic;
		m_elementLoc = ElementLocations.parseStringExpr(elementLocExpr);
		m_delivery = (delivery != null) ? delivery : DeliveryMode.ALL;
		m_qos = (qos != null) ? qos : DEFAULT_QOS;
		m_format = (format != null) ? format : PayloadFormat.JSON;
		m_maxInFlightMessages = (maxInFlightMessages != null) ? maxInFlightMessages : DEFAULT_MAX_IN_FLIGHT_MESSAGES;
		m_overflowPolicy = (overflowPolicy != null) ? overflowPolicy : OverflowPolicy.WAIT;
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
	
	/**
	 * {@link DeliveryMode#ALL}에서 처리 중인 메시지와 대기 중인 메시지를 합한 수의 상한을 반환한다.
	 * {@link DeliveryMode#LATEST}에는 적용되지 않는다(최신 값 하나만 보관).
	 *
	 * @return	상한.
	 */
	@JsonProperty("maxInFlightMessages")
	public int getMaxInFlightMessages() {
		return m_maxInFlightMessages;
	}
	
	@JsonProperty("overflowPolicy")
	public OverflowPolicy getOverflowPolicy() {
		return m_overflowPolicy;
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
		if ( m_maxInFlightMessages != DEFAULT_MAX_IN_FLIGHT_MESSAGES ) {
			options.append(", maxInFlightMessages=").append(m_maxInFlightMessages);
		}
		if ( m_overflowPolicy != OverflowPolicy.WAIT ) {
			options.append(", overflowPolicy=").append(m_overflowPolicy.toJsonValue());
		}
		return String.format("%s -> %s%s", m_topic, m_elementLoc.toStringExpr(), options);
	}
}
