package mdt.endpoint.mqtt;

import java.io.IOException;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
	 * ({@code mdt:asset:mqtt})의 같은 이름 필드와 같은 규칙이다({@link ConnectionConfigRef}). 단, 자산 변수와 달리
	 * 이전 형식인 {@code mqttBrokerConfigName}은 받지 않으며, 적혀 있으면 설정을 읽는 단계에서 거부한다.
	 * {@code subscribers}는 반드시 하나 이상 있어야 하며, 없거나 비어 있으면 설정을 읽는 단계에서 거부한다.
	 */
	// 'mqttBrokerConfigName'은 지원하지 않지만 목록에 둔다. 목록에 없는 필드는 생성자에 전달되기 전에 버려지므로,
	// 빼면 거부하지 못하고 조용히 'default' broker를 쓰게 된다.
	@JsonIncludeProperties({ "mqttBrokerConfig", "mqttBrokerConfigName", "subscribers" })
	public static class MDTConfig {
		private static final String FIELD_BROKER_CONFIG = "mqttBrokerConfig";
		private static final String FIELD_LEGACY_BROKER_CONFIG_NAME = "mqttBrokerConfigName";	// 지원하지 않는 이전 형식
		private static final String FIELD_SUBSCRIBERS = "subscribers";

		private ConnectionConfigRef<MqttBrokerConfig> m_brokerRef;
		private List<MqttElementSubscriber> m_subscribers;
		
		/**
		 * JSON 설정으로부터 만든다.
		 *
		 * @param brokerConfig	{@code mqttBrokerConfig} 값. 문자열은 전역 설정 이름, 객체는 broker 설정이며,
		 * 						없으면 전역 설정의 {@code default}.
		 * @param legacyBrokerConfigName	이전 형식의 {@code mqttBrokerConfigName} 값. 지원하지 않으므로 있으면 거부한다.
		 * @param subscribers	구독 목록. 반드시 하나 이상 있어야 한다.
		 * @throws IOException	{@code mqttBrokerConfig}가 문자열도 객체도 아니거나 broker 설정으로 읽지 못한 경우.
		 * @throws IllegalArgumentException	{@code mqttBrokerConfigName}이 있거나, {@code subscribers}가 없거나
		 * 									비어 있거나 {@code null} 항목이 있는 경우.
		 */
		@JsonCreator
		public MDTConfig(@JsonProperty(FIELD_BROKER_CONFIG) @Nullable JsonNode brokerConfig,
						@JsonProperty(FIELD_LEGACY_BROKER_CONFIG_NAME) @Nullable JsonNode legacyBrokerConfigName,
						@JsonProperty(FIELD_SUBSCRIBERS) @Nullable List<MqttElementSubscriber> subscribers)
			throws IOException {
			// 이전 형식을 조용히 무시하면 의도와 다른 'default' broker에 연결되므로 명시적으로 거부한다.
			Preconditions.checkArgument(legacyBrokerConfigName == null || legacyBrokerConfigName.isNull(),
										"'%s' is not supported: use '%s' (a global config name or an inline broker config)",
										FIELD_LEGACY_BROKER_CONFIG_NAME, FIELD_BROKER_CONFIG);
			m_brokerRef = ConnectionConfigRef.fromNode(brokerConfig, FIELD_BROKER_CONFIG, MqttBrokerConfig.class);
			m_subscribers = checkSubscribers(subscribers);
		}
		
		/**
		 * Java 코드에서 만든다.
		 *
		 * @param brokerRef		broker 설정 참조.
		 * @param subscribers	구독 목록. 반드시 하나 이상 있어야 한다.
		 */
		public MDTConfig(ConnectionConfigRef<MqttBrokerConfig> brokerRef, List<MqttElementSubscriber> subscribers) {
			Preconditions.checkNotNullArgument(brokerRef, "MqttBrokerConfig reference is null");
			m_brokerRef = brokerRef;
			m_subscribers = checkSubscribers(subscribers);
		}
		
		// 구독이 없는 MQTT 엔드포인트는 broker에 연결하지도 않고 아무 일도 하지 않는다. 'mqtt' 항목을 적고
		// 'subscribers'를 빠뜨렸다면(철자 오류 포함) 설정 실수이므로 조용히 넘어가지 않고 거부한다.
		private static List<MqttElementSubscriber> checkSubscribers(@Nullable List<MqttElementSubscriber> subscribers) {
			Preconditions.checkArgument(subscribers != null && !subscribers.isEmpty(),
										"'%s' is required and must not be empty: an MQTT endpoint without subscriptions "
										+ "does nothing", FIELD_SUBSCRIBERS);
			for ( int i = 0; i < subscribers.size(); ++i ) {
				Preconditions.checkArgument(subscribers.get(i) != null, "%s[%s] is null", FIELD_SUBSCRIBERS, i);
			}
			return List.copyOf(subscribers);
		}

		/**
		 * broker 설정 참조를 반환한다.
		 *
		 * @return	전역 설정 이름 또는 인라인 broker 설정을 가리키는 참조.
		 */
		@JsonIgnore
		public ConnectionConfigRef<MqttBrokerConfig> getMqttBrokerConfigRef() {
			return m_brokerRef;
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

		@JsonGetter(FIELD_SUBSCRIBERS)
		public List<MqttElementSubscriber> getSubscribers() {
			return m_subscribers;
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
