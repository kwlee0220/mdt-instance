package mdt.persistence.mqtt;

import java.time.Duration;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import utils.UnitUtils;
import utils.func.FOption;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "brokerUrl", "reconnectInterval", "connectTimeout" })
public class MqttBrokerConfig {
	private static final String DEFAULT_BROKER_URL = "tcp://localhost:1883";
	private static final String DEFAULT_CONNECT_TIMEOUT = "3s";
	private static final String DEFAULT_RECONNECT_INTERVAL = "10s";
	
	private final String m_brokerUrl;
	private final String m_reconnectInterval;
	private final String m_connectTimeout;
	
	public MqttBrokerConfig(@Nullable @JsonProperty("brokerUrl") String brokerUrl,
							@JsonProperty("reconnectInterval") String reconnectTryInterval,
							@Nullable @JsonProperty("connectTimeout") String connectTimeout) {
		m_brokerUrl = FOption.getOrElse(brokerUrl, DEFAULT_BROKER_URL);
		m_reconnectInterval = FOption.getOrElse(reconnectTryInterval,  DEFAULT_RECONNECT_INTERVAL);
		m_connectTimeout = FOption.getOrElse(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
	}
	
	public String getBrokerUrl() {
		return m_brokerUrl;
	}
	
	public String getReconnectInterval() {
		return m_reconnectInterval;
	}
	
	public Duration getConnectTimeoutDuration() {
		return UnitUtils.parseDuration(m_connectTimeout);
	}
	
	public String getConnectTimeout() {
		return m_connectTimeout;
	}
	
	@Override
	public String toString() {
		return String.format("%s: broker=%s, reconnect=%s, connect-timeout=%s",
							getClass().getSimpleName(), getBrokerUrl(), getReconnectInterval(), getConnectTimeout());
	}
}
