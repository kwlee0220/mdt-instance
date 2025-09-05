package mdt.endpoint.mqtt;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonPropertyOrder({ "mqttConfigName", "subscribers" })
@Accessors(prefix="m_")
@Getter @Setter
public class MqttEndpointConfig extends EndpointConfig<MqttEndpoint> {
	private String m_mqttConfigName = "default";
	private List<MqttElementSubscriber> m_subscribers = List.of();
	
	@Override
	public String toString() {
		return String.format("MqttEndpointConfig[mqttConfigName=%s, subscribers=%s]", m_mqttConfigName, m_subscribers);
	}
}
