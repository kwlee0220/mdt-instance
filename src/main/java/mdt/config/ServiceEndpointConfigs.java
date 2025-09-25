package mdt.config;

import utils.async.command.ProgramServiceConfig;

import mdt.endpoint.mqtt.MqttEndpointConfig;
import mdt.endpoint.ros2.Ros2EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ServiceEndpointConfigs {
	private MqttEndpointConfig.MDTConfig m_mqtt;
	private Ros2EndpointConfig.MDTConfig m_ros2;
	private ProgramServiceConfig m_companion;
	
	public MqttEndpointConfig.MDTConfig getMqtt() {
		return m_mqtt;
	}
	public void setMqtt(MqttEndpointConfig.MDTConfig mqtt) {
		m_mqtt = mqtt;
	}
	
	public Ros2EndpointConfig.MDTConfig getRos2() {
		return m_ros2;
	}
	public void setRos2(Ros2EndpointConfig.MDTConfig ros2) {
		m_ros2 = ros2;
	}
	
	public ProgramServiceConfig getCompanion() {
		return m_companion;
	}
	public void setCompanion(ProgramServiceConfig companion) {
		m_companion = companion;
	}
}
