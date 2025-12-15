package mdt.endpoint.ros2;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.java_websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import utils.async.AbstractStatePoller;

/**
 * A class to connect to an MQTT broker with automatic reconnection.
 * <p>
 * This class attempts to connect to the specified MQTT broker and will retry
 * if the connection fails. The reconnection attempts will be made at a
 * specified interval until a successful connection is established.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class WebSocketReconnector extends AbstractStatePoller<WebSocketClient> {
	private static final Logger s_logger = LoggerFactory.getLogger(WebSocketReconnector.class);
	
	private final Supplier<WebSocketClient> m_wsClientFact;
	private final long m_timeoutMillis;
	
	WebSocketReconnector(Supplier<WebSocketClient> wsClientFact, @NonNull Duration reconnectInterval) {
		super(reconnectInterval);
		Preconditions.checkArgument(wsClientFact != null, "WebSocket client factory is null");
		
		m_wsClientFact = wsClientFact;
		m_timeoutMillis = Math.max(reconnectInterval.toMillis() - 10, 0);
		
		setLogger(s_logger);
	}

	@Override
	protected Optional<WebSocketClient> pollState() throws Exception {
		WebSocketClient wsClient = m_wsClientFact.get();
		getLogger().debug("retrying connection to {}", wsClient.getURI());
		
		// WebSocket server에 연결을 시도한다.
		boolean connected = wsClient.connectBlocking(m_timeoutMillis, TimeUnit.MILLISECONDS);
		if ( connected ) {
			getLogger().info("connected to {}", wsClient.getURI());
			
			// WebSocket server에 연결된 경우 {@link WebSocketClient} 객체를 반환하고 loop를 종료시킨다
			return Optional.of(wsClient);
		}
		else {
			return Optional.empty();
		}
	}
}
