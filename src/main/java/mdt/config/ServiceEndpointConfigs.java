package mdt.config;

import java.util.Map;

import mdt.endpoint.companion.CompanionConfig;
import mdt.endpoint.mqtt.MqttEndpointConfig;
import mdt.endpoint.opcontrol.OperationControlEndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ServiceEndpointConfigs {
	private MqttEndpointConfig.MDTConfig m_mqtt;
	private CompanionConfig m_companion;
	private Map<String,CompanionConfig> m_companions = Map.of();
	private OperationControlEndpointConfig.MDTConfig m_operationControl;
	
	public MqttEndpointConfig.MDTConfig getMqtt() {
		return m_mqtt;
	}
	public void setMqtt(MqttEndpointConfig.MDTConfig mqtt) {
		m_mqtt = mqtt;
	}
	
	public CompanionConfig getCompanion() {
		return m_companion;
	}
	public void setCompanion(CompanionConfig companion) {
		m_companion = companion;
	}
	
	/**
	 * 이름을 붙인 동반 프로그램들을 반환한다. 단일 {@link #getCompanion() companion}과 함께 쓸 수 있다.
	 * 
	 * @return	동반 프로그램 이름과 설정의 맵(설정 파일의 순서를 따름). 없으면 빈 맵.
	 */
	public Map<String,CompanionConfig> getCompanions() {
		return m_companions;
	}
	public void setCompanions(Map<String,CompanionConfig> companions) {
		m_companions = (companions != null) ? companions : Map.of();
	}
	
	/**
	 * AAS 비동기 연산의 취소 엔드포인트 설정({@code operationControl})을 반환한다.
	 * 
	 * @return	취소 엔드포인트 설정. 없으면 {@code null}이고 취소 엔드포인트를 열지 않는다.
	 */
	public OperationControlEndpointConfig.MDTConfig getOperationControl() {
		return m_operationControl;
	}
	public void setOperationControl(OperationControlEndpointConfig.MDTConfig operationControl) {
		m_operationControl = operationControl;
	}
}
