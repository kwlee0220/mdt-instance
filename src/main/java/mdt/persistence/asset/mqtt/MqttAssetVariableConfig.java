package mdt.persistence.asset.mqtt;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.json.JacksonUtils;

import mdt.MDTGlobalConfigurations;
import mdt.client.support.MqttBrokerConfig;
import mdt.model.MDTModelSerDe;
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

	private String m_mqttBrokerConfigName;
    private MqttBrokerConfig m_mqttBrokerConfig;
    private String m_topic;
	
	private MqttAssetVariableConfig() { }
	
	public String getMqttBrokerConfigName() {
		return m_mqttBrokerConfigName;
	}
	
	public MqttBrokerConfig getMqttBrokerConfig() {
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

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}
    
    @Override
    public String toString() {
		return String.format("broker=%s, topic=%s", getMqttBrokerConfig().getBrokerUrl(), m_topic);
	}
	
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);
		gen.writeStringField(FIELD_BROKER_CONFIG_NAME, m_mqttBrokerConfigName);
		gen.writeObjectField(FIELD_BROKER_CONFIG, m_mqttBrokerConfig);
		gen.writeStringField(FIELD_TOPIC, m_topic);
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
		
		return config;
	}
}
