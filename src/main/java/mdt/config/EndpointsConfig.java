package mdt.config;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class EndpointsConfig {
	private MDTMqttEndpointConfig m_mqtt;
}
