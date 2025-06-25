package mdt.persistence.opcua;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.api.UaClient;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import utils.async.AbstractLoopExecution;
import utils.func.FOption;

/**
 * A class to connect to an MQTT broker with automatic reconnection.
 * <p>
 * This class attempts to connect to the specified MQTT broker and will retry
 * if the connection fails. The reconnection attempts will be made at a
 * specified interval until a successful connection is established.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OpcUaServerReconnect extends AbstractLoopExecution<UaClient> {
	private static final Logger s_logger = LoggerFactory.getLogger(OpcUaServerReconnect.class);
	
	private final String m_endpointUrl;
	private final Duration m_reconnectTrialInterval;
	private final @Nullable SessionActivityListener m_listener;
	
	@Override protected void initializeLoop() throws Exception { }
	@Override protected void finalizeLoop() throws Exception { }
	
	private OpcUaServerReconnect(Builder builder) {
		Preconditions.checkNotNull(builder.m_opcUaServerEndpoint);
		Preconditions.checkNotNull(builder.m_reconnectTryInterval);
		
		m_endpointUrl = builder.m_opcUaServerEndpoint;
		m_listener = builder.m_activityListener;
		m_reconnectTrialInterval = builder.m_reconnectTryInterval;
		
		setLogger(s_logger);
	}

	@Override
	protected FOption<UaClient> iterate(long loopIndex) throws Exception {
		if ( getLogger().isDebugEnabled() ) {
			getLogger().debug("retrying {}-th connection to {}", loopIndex+1, m_endpointUrl);
		}
		
		Instant started = Instant.now();
		try {
			// MQTT Broker에 연결을 시도한다.
			UaClient opcUaClient = connectToOpcUaServer();
			System.out.println("connected to OpcUaServer: endpoint=" + m_endpointUrl);
			
			// MQTT Broker에 연결된 경우 {@link MqttClient} 객체를 반환하고 loop를 종료시킨다
			return FOption.of(opcUaClient);
		}
		catch ( UaException e ) {
			Duration elapsed = Duration.between(started, Instant.now());
			long remains = m_reconnectTrialInterval.minus(elapsed).toMillis();
			if ( remains > 10 ) {
				TimeUnit.MILLISECONDS.sleep(remains);
			}
			
			// MQTT Broker에 연결되지 않은 경우 {@link FOption#empty()}를 반환하여
			// loop를 계속 수행하도록 한다.
			return FOption.empty();
		}
	}
	
	private UaClient connectToOpcUaServer() throws UaException, InterruptedException, ExecutionException {
		OpcUaClient client = OpcUaClient.create(m_endpointUrl);
		client.addSessionActivityListener(m_listener);
		UaClient uac = client.connect().get();
		
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("connected to OpcUaServer: endpoint={}", m_endpointUrl);
		}
		
		return uac;
	}
	
	public static Builder builder() {
		return new Builder();
	}
	public static class Builder {
		private String m_opcUaServerEndpoint;
		private Duration m_reconnectTryInterval = Duration.ofSeconds(10);
		private @Nullable SessionActivityListener m_activityListener;
		
		public OpcUaServerReconnect build() {
			return new OpcUaServerReconnect(this);
		}
		
		/**
		 * MQTT Broker의 URI를 설정한다.
		 *
		 * @param uri MQTT Broker URI
		 * @return 본 객체.
		 */
		public Builder opcUaServerEndpoint(String uri) {
			m_opcUaServerEndpoint = uri;
			return this;
		}
		
		/**
		 * MQTT connection에 사용할 callback 객체를 설정한다.
		 *
		 * @param listener	callback 객체
		 * @return	본 객체.
		 */
		public Builder activityListener(SessionActivityListener listener) {
			m_activityListener = listener;
			return this;
		}
		
		/**
		 * MQTT Broker에 재접속을 시도하는 간격을 설정한다.
		 *
		 * @param interval 재접속 시도 간격
		 * @return 본 객체.
		 */
		public Builder reconnectTryInterval(Duration interval) {
			m_reconnectTryInterval = interval;
			return this;
		}
	}
}
