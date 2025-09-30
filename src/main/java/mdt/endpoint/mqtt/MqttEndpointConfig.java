package mdt.endpoint.mqtt;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.MDTGlobalConfigurations;
import mdt.client.support.MqttBrokerConfig;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "mqttBrokerConfig", "subscribers" })
public class MqttEndpointConfig extends EndpointConfig<MqttEndpoint> {
	private final MDTConfig m_conf;
	
	public MqttEndpointConfig(MDTConfig conf) {
		m_conf = conf;
	}
	
	public MqttBrokerConfig getMqttBrokerConfig() {
		if ( m_conf.m_mqttBrokerConfig instanceof MqttBrokerConfig mqttConf ) {
			return mqttConf;
		}
		else if ( m_conf.m_mqttBrokerConfig instanceof String configName ) {
			return MDTGlobalConfigurations.getMqttBrokerConfig(configName);
		}
		else {
			throw new IllegalArgumentException("unsupported MqttBrokerConfig: " + m_conf.m_mqttBrokerConfig);
		}
	}
	
	public List<MqttElementSubscriber> getSubscribers() {
		return m_conf.getSubscribers();
	}
	
	@Override
	public String toString() {
		return String.format("MqttEndpointConfig[broker=%s, subscribers=%s]",
							getMqttBrokerConfig().getBrokerUrl(), getSubscribers());
	}

	@JsonIncludeProperties({ "mqttBrokerConfig", "subscribers" })
	@Getter @Setter
	@Accessors(prefix="m_")
	public static class MDTConfig {
	    private Object m_mqttBrokerConfig = "default";;
		private List<MqttElementSubscriber> m_subscribers = List.of();
	}
}