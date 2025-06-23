package mdt.config;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.endpoint.mqtt.MDTMqttEndpointConfig;
import mdt.endpoint.ros2.MDTRos2EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class MDTEndpointConfigs {
	private MDTMqttEndpointConfig m_mqtt;
	private MDTRos2EndpointConfig m_ros2;
}
