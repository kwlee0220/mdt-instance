package mdt.endpoint.mqtt;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import utils.func.FOption;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttEndpointConfig extends EndpointConfig<MqttEndpoint> {
	private String m_mqttConfig = "default";
	private List<MqttElementSubscriber> m_subscribers;

	@JsonProperty("mqttConfig")
	public String getMqttConfig() {
		return m_mqttConfig;
	}

	@JsonProperty("mqttConfig")
	public void setMqttConfig(String mqttConfig) {
		m_mqttConfig = mqttConfig;
	}

	@JsonProperty("subscribers")
	public List<MqttElementSubscriber> getSubscribers() {
		return FOption.getOrElse(m_subscribers, List.of());
	}

	@JsonProperty("subscribers")
	public void setSubscribers(List<MqttElementSubscriber> subscribers) {
		m_subscribers = subscribers;
	}
}
