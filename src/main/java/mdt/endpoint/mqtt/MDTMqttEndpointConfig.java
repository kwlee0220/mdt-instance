package mdt.endpoint.mqtt;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

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
