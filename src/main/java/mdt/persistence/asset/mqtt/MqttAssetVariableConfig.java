package mdt.persistence.asset.mqtt;

import java.io.IOException;
import java.time.Duration;

import org.eclipse.paho.client.mqttv3.MqttTopic;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.UnitUtils;
import utils.json.JacksonUtils;

import mdt.MDTGlobalConfigurations;
import mdt.client.support.MqttBrokerConfig;
import mdt.model.ResourceNotFoundException;
import mdt.persistence.asset.AbstractAssetVariableConfig;
import mdt.persistence.asset.AssetVariableConfig;
import mdt.persistence.asset.ConnectionConfigRef;


/**
 * {@link MqttAssetVariable}의 설정.
 * <p>
 * 설정 예:
 * <pre>{@code
 * {
 *   "@type": "mdt:asset:mqtt",
 *   "element": "Telemetry:Measurements",
 *   "mqttBrokerConfig": "factory",
 *   "topic": "machines/machine01/measurements",
 *   "qos": 1,
 *   "retained": false,
 *   "publishTimeout": "10s",
 *   "whenDisconnected": "skip"
 * }
 * }</pre>
 * {@code mqttBrokerConfig}는 전역 설정 파일의 {@code mqttBrokers} 항목 이름(문자열)이거나
 * broker 설정 객체이다. 생략하면 {@code "default"}이다. 이전 형식의 {@code mqttBrokerConfigName}도
 * 받지만, {@code mqttBrokerConfig}와 함께 사용할 수는 없다({@link ConnectionConfigRef}).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttAssetVariableConfig extends AbstractAssetVariableConfig implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:mqtt";

	private static final String FIELD_BROKER_CONFIG_NAME = "mqttBrokerConfigName";	// 이전 형식
	private static final String FIELD_BROKER_CONFIG = "mqttBrokerConfig";
	private static final String FIELD_TOPIC = "topic";
	private static final String FIELD_QOS = "qos";
	private static final String FIELD_RETAINED = "retained";
	private static final String FIELD_PUBLISH_TIMEOUT = "publishTimeout";
	private static final String FIELD_WHEN_DISCONNECTED = "whenDisconnected";

	public static final int DEFAULT_QOS = 1;
	public static final boolean DEFAULT_RETAINED = false;
	public static final Duration DEFAULT_PUBLISH_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * 메시지를 broker에 전달하지 못했을 때 값 갱신({@link MqttAssetVariable#update})을 처리하는 방법.
	 * <p>
	 * broker와 연결되어 있지 않은 경우와, {@code publishTimeout} 안에 broker의 전달 확인을 받지 못한
	 * 경우에 적용된다.
	 */
	public enum DisconnectedPolicy {
		/** 갱신을 실패시킨다. 이 경우 AAS 요소의 값도 갱신되지 않는다. */
		FAIL,
		/** 메시지 발행을 건너뛰고 경고 로그만 남긴다. AAS 요소의 값은 정상적으로 갱신된다. */
		SKIP;

		/**
		 * 설정 파일에 기술되는 이름을 반환한다.
		 *
		 * @return	소문자 이름 ({@code "fail"} 또는 {@code "skip"}).
		 */
		public String toJsonValue() {
			return name().toLowerCase();
		}

		/**
		 * 설정 파일에 기술된 이름으로부터 정책을 구한다. 대소문자는 구분하지 않는다.
		 *
		 * @param value	정책 이름.
		 * @return	해당 정책.
		 * @throws IOException	알 수 없는 이름인 경우.
		 */
		public static DisconnectedPolicy fromJsonValue(String value) throws IOException {
			for ( DisconnectedPolicy policy: values() ) {
				if ( policy.name().equalsIgnoreCase(value) ) {
					return policy;
				}
			}
			throw new IOException(String.format("invalid '%s' value: %s (expected: fail or skip)",
												FIELD_WHEN_DISCONNECTED, value));
		}
	}
	public static final DisconnectedPolicy DEFAULT_DISCONNECTED_POLICY = DisconnectedPolicy.SKIP;

	private ConnectionConfigRef<MqttBrokerConfig> m_brokerRef;
    private String m_topic;
    private int m_qos = DEFAULT_QOS;
    private boolean m_retained = DEFAULT_RETAINED;
    private Duration m_publishTimeout = DEFAULT_PUBLISH_TIMEOUT;
    private DisconnectedPolicy m_whenDisconnected = DEFAULT_DISCONNECTED_POLICY;

	private MqttAssetVariableConfig() { }

	/**
	 * 전역 설정 파일에서 사용할 broker 설정의 이름을 반환한다.
	 *
	 * @return	broker 설정 이름. broker 설정이 직접 기술된 경우는 {@code null}.
	 */
	public String getMqttBrokerConfigName() {
		return m_brokerRef.name();
	}

	/**
	 * 사용할 MQTT broker 설정을 반환한다.
	 * <p>
	 * 설정에 broker 정보가 직접 기술되어 있으면 이를 반환하고, 그렇지 않으면
	 * 전역 설정 파일의 {@code mqttBrokers.<이름>}을 읽어 반환한다.
	 * 전역 설정은 호출할 때마다 파일에서 다시 읽으므로, 호출자는 결과를 보관하여 사용한다.
	 *
	 * @return	MQTT broker 설정.
	 * @throws ResourceNotFoundException	전역 설정에서 해당 이름의 broker 설정을 찾지 못한 경우.
	 * @throws IllegalStateException	전역 설정 파일이 지정되지 않았거나 존재하지 않는 경우.
	 */
	public MqttBrokerConfig getMqttBrokerConfig() throws ResourceNotFoundException {
		return m_brokerRef.resolve(MDTGlobalConfigurations::getMqttBrokerConfig);
	}

	public String getTopic() {
		return m_topic;
	}

	/**
	 * 발행 QoS를 반환한다. 설정에 기술되지 않은 경우는 {@link #DEFAULT_QOS}이다.
	 *
	 * @return	QoS (0, 1, 2).
	 */
	public int getQos() {
		return m_qos;
	}

	/**
	 * 발행 메시지를 broker가 보관(retained)하게 할지 여부를 반환한다.
	 * 설정에 기술되지 않은 경우는 {@link #DEFAULT_RETAINED}이다.
	 *
	 * @return	retained 여부.
	 */
	public boolean isRetained() {
		return m_retained;
	}

	/**
	 * QoS 1, 2 발행에서 broker의 전달 확인을 기다리는 최대 시간을 반환한다.
	 * 설정에 기술되지 않은 경우는 {@link #DEFAULT_PUBLISH_TIMEOUT}이다.
	 *
	 * @return	최대 대기 시간.
	 */
	public Duration getPublishTimeout() {
		return m_publishTimeout;
	}

	/**
	 * 메시지를 broker에 전달하지 못했을 때의 갱신 처리 정책을 반환한다.
	 * 설정에 기술되지 않은 경우는 {@link #DEFAULT_DISCONNECTED_POLICY}이다.
	 *
	 * @return	갱신 처리 정책.
	 */
	public DisconnectedPolicy getWhenDisconnected() {
		return m_whenDisconnected;
	}

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}

    @Override
    public String toString() {
		// 전역 설정 파일을 읽지 않도록 broker 설정을 해석하지 않고 설정에 기술된 내용만 사용한다.
		String broker = m_brokerRef.toDisplayString("mqttBrokers", MqttBrokerConfig::getBrokerUrl);
		return String.format("%s, broker=%s, topic=%s, qos=%d%s", super.toString(), broker, m_topic, m_qos,
								m_retained ? ", retained" : "");
	}

	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);
		m_brokerRef.serialize(gen, FIELD_BROKER_CONFIG);
		gen.writeStringField(FIELD_TOPIC, m_topic);
		gen.writeNumberField(FIELD_QOS, m_qos);
		gen.writeBooleanField(FIELD_RETAINED, m_retained);
		gen.writeStringField(FIELD_PUBLISH_TIMEOUT, m_publishTimeout.toString());
		gen.writeStringField(FIELD_WHEN_DISCONNECTED, m_whenDisconnected.toJsonValue());
	}

	/**
	 * JSON 노드로부터 {@link MqttAssetVariableConfig} 객체를 생성한다.
	 *
	 * @param jnode	JSON 노드
	 * @return	생성된 {@link MqttAssetVariableConfig} 객체.
	 * @throws IOException	필드 값이 잘못된 경우.
	 */
	public static MqttAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		MqttAssetVariableConfig config = new MqttAssetVariableConfig();
		config.loadFields(jnode);

		config.m_brokerRef = ConnectionConfigRef.load(jnode, FIELD_BROKER_CONFIG, FIELD_BROKER_CONFIG_NAME,
														MqttBrokerConfig.class);

		config.m_topic = JacksonUtils.getStringField(jnode, FIELD_TOPIC);
		try {
			// 발행 topic에는 와일드카드('+', '#')를 사용할 수 없다.
			MqttTopic.validate(config.m_topic, false);
		}
		catch ( IllegalArgumentException e ) {
			throw new IOException(String.format("invalid '%s' value: '%s', cause=%s",
												FIELD_TOPIC, config.m_topic, e.getMessage()));
		}

		JsonNode qosNode = JacksonUtils.getFieldOrNull(jnode, FIELD_QOS);
		if ( qosNode != null && !qosNode.isNull() ) {
			if ( !qosNode.canConvertToInt() || qosNode.asInt() < 0 || qosNode.asInt() > 2 ) {
				throw new IOException(String.format("invalid '%s' value: %s (expected: 0, 1, or 2)",
													FIELD_QOS, qosNode));
			}
			config.m_qos = qosNode.asInt();
		}

		JsonNode retainedNode = JacksonUtils.getFieldOrNull(jnode, FIELD_RETAINED);
		if ( retainedNode != null && !retainedNode.isNull() ) {
			if ( !retainedNode.isBoolean() ) {
				throw new IOException(String.format("invalid '%s' value: %s (expected: true or false)",
													FIELD_RETAINED, retainedNode));
			}
			config.m_retained = retainedNode.asBoolean();
		}

		String timeoutStr = JacksonUtils.getStringFieldOrNull(jnode, FIELD_PUBLISH_TIMEOUT);
		if ( timeoutStr != null ) {
			Duration timeout;
			try {
				timeout = UnitUtils.parseDuration(timeoutStr);
			}
			catch ( Exception e ) {
				throw new IOException(String.format("invalid '%s' value: %s, cause=%s",
													FIELD_PUBLISH_TIMEOUT, timeoutStr, e.getMessage()));
			}
			if ( timeout.isNegative() || timeout.isZero() ) {
				throw new IOException(String.format("'%s' must be positive: %s", FIELD_PUBLISH_TIMEOUT, timeoutStr));
			}
			config.m_publishTimeout = timeout;
		}

		String policy = JacksonUtils.getStringFieldOrNull(jnode, FIELD_WHEN_DISCONNECTED);
		if ( policy != null ) {
			config.m_whenDisconnected = DisconnectedPolicy.fromJsonValue(policy);
		}

		return config;
	}
}
