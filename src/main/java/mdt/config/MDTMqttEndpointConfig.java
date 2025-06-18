package mdt.config;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.endpoint.mqtt.MqttElementSubscriber;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class MDTMqttEndpointConfig {
	private String m_mqttConfig = "default";
	private List<MqttElementSubscriber> m_subscribers;
}
