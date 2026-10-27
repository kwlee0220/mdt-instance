package mdt.persistence.asset.mqtt;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.json.JacksonUtils;

import mdt.MDTGlobalConfigurations;
import mdt.client.support.MqttBrokerConfig;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;
import mdt.persistence.asset.AbstractAssetVariableConfig;
import mdt.persistence.asset.AssetVariableConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttAssetVariableConfig extends AbstractAssetVariableConfig implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:mqtt";
	
	private static final String FIELD_BROKER_CONFIG_NAME = "mqttBrokerConfigName";
	private static final String FIELD_BROKER_CONFIG = "mqttBrokerConfig";
	private static final String FIELD_TOPIC = "topic";
	private static final String FIELD_WHEN_DISCONNECTED = "whenDisconnected";

	/**
	 * MQTT broker와 연결되어 있지 않을 때 값 갱신({@link MqttAssetVariable#update})을 처리하는 방법.
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

	private String m_mqttBrokerConfigName;
    private MqttBrokerConfig m_mqttBrokerConfig;
    private String m_topic;
    private DisconnectedPolicy m_whenDisconnected = DEFAULT_DISCONNECTED_POLICY;

	private MqttAssetVariableConfig() { }
	
	public String getMqttBrokerConfigName() {
		return m_mqttBrokerConfigName;
	}
	
	/**
	 * 사용할 MQTT broker 설정을 반환한다.
	 * <p>
	 * 설정에 broker 정보({@code mqttBrokerConfig})가 직접 기술되어 있으면 이를 반환하고, 그렇지 않으면
	 * 전역 설정 파일의 {@code mqttBrokers.<mqttBrokerConfigName>}을 읽어 반환한다.
	 * 전역 설정은 호출할 때마다 파일에서 다시 읽으므로, 호출자는 결과를 보관하여 사용한다.
	 *
	 * @return	MQTT broker 설정.
	 * @throws ResourceNotFoundException	전역 설정에서 해당 이름의 broker 설정을 찾지 못한 경우.
	 * @throws IllegalStateException	전역 설정 파일이 지정되지 않았거나 존재하지 않는 경우.
	 */
	public MqttBrokerConfig getMqttBrokerConfig() throws ResourceNotFoundException {
		if ( m_mqttBrokerConfig != null ) {
			return m_mqttBrokerConfig;
		}
		else {
			return MDTGlobalConfigurations.getMqttBrokerConfig(m_mqttBrokerConfigName);
		}
	}
	
	public String getTopic() {
		return m_topic;
	}

	/**
	 * MQTT broker와 연결되어 있지 않을 때의 갱신 처리 정책을 반환한다.
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
		String broker = (m_mqttBrokerConfig != null)
						? m_mqttBrokerConfig.getBrokerUrl()
						: String.format("mqttBrokers.%s", m_mqttBrokerConfigName);
		return String.format("%s, broker=%s, topic=%s", super.toString(), broker, m_topic);
	}
	
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);
		gen.writeStringField(FIELD_BROKER_CONFIG_NAME, m_mqttBrokerConfigName);
		gen.writeObjectField(FIELD_BROKER_CONFIG, m_mqttBrokerConfig);
		gen.writeStringField(FIELD_TOPIC, m_topic);
		gen.writeStringField(FIELD_WHEN_DISCONNECTED, m_whenDisconnected.toJsonValue());
	}
	
	/**
	 * JSON 노드로부터 {@link MqttAssetVariableConfig} 객체를 생성한다.
	 * 
	 * @param jnode	JSON 노드
	 * @return	생성된 {@link MqttAssetVariableConfig} 객체.
	 * @throws IOException 
	 */
	public static MqttAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		MqttAssetVariableConfig config = new MqttAssetVariableConfig();
		config.loadFields(jnode);
		
		config.m_mqttBrokerConfigName = JacksonUtils.getStringFieldOrDefault(jnode,
																			FIELD_BROKER_CONFIG_NAME, "default");
		JsonNode configNode = JacksonUtils.getFieldOrNull(jnode, FIELD_BROKER_CONFIG);
		config.m_mqttBrokerConfig = MDTModelSerDe.readValue(configNode, MqttBrokerConfig.class);
		
		config.m_topic = JacksonUtils.getStringField(jnode, FIELD_TOPIC);

		String policy = JacksonUtils.getStringFieldOrNull(jnode, FIELD_WHEN_DISCONNECTED);
		if ( policy != null ) {
			config.m_whenDisconnected = DisconnectedPolicy.fromJsonValue(policy);
		}

		return config;
	}
}
