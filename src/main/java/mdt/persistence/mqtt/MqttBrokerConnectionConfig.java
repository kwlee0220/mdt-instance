package mdt.persistence.mqtt;

import java.time.Duration;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.experimental.Accessors;

import utils.UnitUtils;
import utils.func.FOption;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter
@Accessors(prefix="m_")
@JsonIncludeProperties({ "brokerUrl", "reconnectTryInterval", "publishTimeout" })
public class MqttBrokerConnectionConfig {
	private static final String DEFAULT_BROKER_URL = "tcp://localhost:1883";
	private static final String DEFAULT_PUBLISH_TIMEOUT = "10s";
	
	private final String m_brokerUrl;
	private final String m_reconnectTryInterval;
	private final String m_publishTimeout;
	
	public MqttBrokerConnectionConfig(@Nullable @JsonProperty("brokerUrl") String brokerUrl,
										@JsonProperty("reconnectTryInterval") String reconnectTryInterval,
										@Nullable @JsonProperty("publishTimeout") String publishTimeout) {
		m_brokerUrl = FOption.getOrElse(brokerUrl, DEFAULT_BROKER_URL);
		m_reconnectTryInterval = FOption.getOrElse(reconnectTryInterval,  "10s");
		m_publishTimeout = FOption.getOrElse(publishTimeout, DEFAULT_PUBLISH_TIMEOUT);
	}
	
	public Duration getPublishTimeout() {
		return UnitUtils.parseDuration(m_publishTimeout);
	}
	
	@JsonProperty("publishTimeout")
	public String getPublishTimeoutForJackson() {
		return m_publishTimeout;
	}
	
	@Override
	public String toString() {
		return String.format("%s: broker=%s, reconnect=%s, publish-timeout=%s",
							getClass().getSimpleName(), m_brokerUrl, m_reconnectTryInterval, m_publishTimeout);
	}
}
