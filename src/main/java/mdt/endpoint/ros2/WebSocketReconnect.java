package mdt.endpoint.ros2;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
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
public class WebSocketReconnect extends AbstractLoopExecution<WebSocketClient> {
	private static final Logger s_logger = LoggerFactory.getLogger(WebSocketReconnect.class);
	
	private final WebSocketClient m_wsClient;
	private final Duration m_reconnectTrialInterval;
	
	@Override protected void initializeLoop() throws Exception { }
	@Override protected void finalizeLoop() throws Exception { }
	
	private WebSocketReconnect(Builder builder) {
		Preconditions.checkNotNull(builder.m_wsClient);
		Preconditions.checkNotNull(builder.m_reconnectTryInterval);
		
		m_wsClient = builder.m_wsClient;
		m_reconnectTrialInterval = builder.m_reconnectTryInterval;
		
		setLogger(s_logger);
	}

	@Override
	protected FOption<WebSocketClient> iterate(long loopIndex) throws Exception {
		if ( getLogger().isDebugEnabled() ) {
			getLogger().debug("retrying {}-th connection to {}", loopIndex+1, m_wsClient.getURI());
		}
		
		Instant started = Instant.now();
		try {
			// WebSocket server에 연결을 시도한다.
			m_wsClient.connectBlocking(m_reconnectTrialInterval.toMillis(), TimeUnit.MILLISECONDS);
			getLogger().info("connected to {}", m_wsClient.getURI());
			
			// WebSocket server에 연결된 경우 {@link WebSocketClient} 객체를 반환하고 loop를 종료시킨다
			return FOption.of(m_wsClient);
		}
		catch ( Exception e ) {
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
	
	public static Builder builder() {
		return new Builder();
	}
	public static class Builder {
		private WebSocketClient m_wsClient;
		private Duration m_reconnectTryInterval = Duration.ofSeconds(10);
		
		public WebSocketReconnect build() {
			return new WebSocketReconnect(this);
		}
		
		public Builder webSocketClient(WebSocketClient wsClient) {
			m_wsClient = wsClient;
			return this;
		}
		
		public Builder reconnectTryInterval(Duration interval) {
			m_reconnectTryInterval = interval;
			return this;
		}
	}
}
