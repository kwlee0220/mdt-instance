package mdt.endpoint.mqtt;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import utils.Preconditions;

import mdt.ConnectionConfigRef;
import mdt.MDTGlobalConfigurations;
import mdt.client.support.MqttBrokerConfig;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 * {@link MqttEndpoint}의 FA³ST endpoint 설정.
 * <p>
 * MDT 인스턴스 설정의 {@code serviceEndpoints.mqtt} 항목({@link MDTConfig})을 감싼다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "mqttBrokerConfig", "subscribers" })
public class MqttEndpointConfig extends EndpointConfig<MqttEndpoint> {
	private final MDTConfig m_conf;

	public MqttEndpointConfig(MDTConfig conf) {
		m_conf = conf;
	}

	/**
	 * 사용할 MQTT broker 설정을 반환한다.
	 * <p>
	 * 설정에 broker가 객체로 기술되어 있으면 그대로 반환하고, 이름이면 전역 설정의 {@code mqttBrokers}에서 조회한다.
	 * 전역 설정은 호출할 때마다 다시 읽으므로 호출자는 결과를 보관하여 사용한다.
	 *
	 * @return	broker 설정.
	 */
	public MqttBrokerConfig getMqttBrokerConfig() {
		return m_conf.getMqttBrokerConfigRef().resolve(MDTGlobalConfigurations::getMqttBrokerConfig);
	}

	public List<MqttElementSubscriber> getSubscribers() {
		return m_conf.getSubscribers();
	}

	@Override
	public String toString() {
		return "MqttEndpointConfig[" + m_conf.toDisplayString() + "]";
	}

	/**
	 * MDT 인스턴스 설정의 {@code serviceEndpoints.mqtt} 항목.
	 * <pre>{@code
	 * "mqtt": {
	 *   "mqttBrokerConfig": "factory",                              // 전역 설정 mqttBrokers.factory
	 *   "subscribers": [ { "topic": "...", "element": "..." } ]
	 * }
	 * "mqtt": {
	 *   "mqttBrokerConfig": { "brokerUrl": "tcp://broker:1883" },   // broker 설정을 직접 기술
	 *   "subscribers": [ ... ]
	 * }
	 * }</pre>
	 * {@code mqttBrokerConfig}를 생략하면 전역 설정의 {@code default}를 사용한다. MQTT 자산 변수
	 * ({@code mdt:asset:mqtt})의 같은 이름 필드와 같은 규칙이다({@link ConnectionConfigRef}).
	 */
	@JsonIncludeProperties({ "mqttBrokerConfig", "subscribers" })
	public static class MDTConfig {
		private static final String FIELD_BROKER_CONFIG = "mqttBrokerConfig";

		private ConnectionConfigRef<MqttBrokerConfig> m_brokerRef
														= ConnectionConfigRef.ofName(ConnectionConfigRef.DEFAULT_NAME);
		private List<MqttElementSubscriber> m_subscribers = List.of();

		/**
		 * broker 설정 참조를 반환한다.
		 *
		 * @return	전역 설정 이름 또는 인라인 broker 설정을 가리키는 참조.
		 */
		@JsonIgnore
		public ConnectionConfigRef<MqttBrokerConfig> getMqttBrokerConfigRef() {
			return m_brokerRef;
		}

		@JsonIgnore
		public void setMqttBrokerConfigRef(ConnectionConfigRef<MqttBrokerConfig> ref) {
			Preconditions.checkNotNullArgument(ref, "MqttBrokerConfig reference is null");
			m_brokerRef = ref;
		}

		/**
		 * JSON의 {@code mqttBrokerConfig} 값을 읽는다. 문자열은 전역 설정 이름, 객체는 broker 설정으로 해석한다.
		 *
		 * @param node	필드 값.
		 * @throws IOException	값이 문자열도 객체도 아니거나 broker 설정으로 읽지 못한 경우.
		 */
		@JsonSetter(FIELD_BROKER_CONFIG)
		public void setMqttBrokerConfig(JsonNode node) throws IOException {
			m_brokerRef = ConnectionConfigRef.fromNode(node, FIELD_BROKER_CONFIG, MqttBrokerConfig.class);
		}

		/**
		 * JSON에 기록할 {@code mqttBrokerConfig} 값을 반환한다. 지정된 형태(이름 또는 객체)를 그대로 유지한다.
		 *
		 * @return	전역 설정 이름(문자열) 또는 broker 설정 객체.
		 */
		@JsonGetter(FIELD_BROKER_CONFIG)
		public Object getMqttBrokerConfig() {
			return (m_brokerRef.inline() != null) ? m_brokerRef.inline() : m_brokerRef.name();
		}

		public List<MqttElementSubscriber> getSubscribers() {
			return m_subscribers;
		}

		public void setSubscribers(List<MqttElementSubscriber> subscribers) {
			m_subscribers = (subscribers != null) ? subscribers : List.of();
		}

		/**
		 * 로그에 출력할 문자열을 반환한다. 전역 설정 파일은 읽지 않는다.
		 *
		 * @return	broker와 구독 목록을 담은 문자열.
		 */
		String toDisplayString() {
			String broker = m_brokerRef.toDisplayString("mqttBrokers", MqttBrokerConfig::getBrokerUrl);
			return String.format("broker=%s, subscribers=%s", broker, m_subscribers);
		}

		@Override
		public String toString() {
			return "MqttEndpoint.MDTConfig[" + toDisplayString() + "]";
		}
	}
}
