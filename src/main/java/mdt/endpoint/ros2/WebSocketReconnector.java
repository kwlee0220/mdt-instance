package mdt.endpoint.ros2;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.checkerframework.checker.nullness.qual.NonNull;
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
public class WebSocketReconnector extends AbstractLoopExecution<WebSocketClient> {
	private static final Logger s_logger = LoggerFactory.getLogger(WebSocketReconnector.class);
	
	private final Supplier<WebSocketClient> m_wsClientFact;
	private final Duration m_reconnectTrialInterval;
	
	@Override protected void initializeLoop() throws Exception { }
	@Override protected void finalizeLoop() throws Exception { }
	
	WebSocketReconnector(Supplier<WebSocketClient> wsClientFact, @NonNull Duration reconnectInterval) {
		Preconditions.checkArgument(wsClientFact != null, "WebSocket client factory is null");
		Preconditions.checkArgument(reconnectInterval != null, "reconnect interval is null");
		
		m_wsClientFact = wsClientFact;
		m_reconnectTrialInterval = reconnectInterval;
		
		setLogger(s_logger);
	}

	@Override
	protected FOption<WebSocketClient> iterate(long loopIndex) throws Exception {
		WebSocketClient wsClient = m_wsClientFact.get();
		if ( getLogger().isDebugEnabled() ) {
			getLogger().debug("retrying {}-th connection to {}", loopIndex+1, wsClient.getURI());
		}
		
		Instant started = Instant.now();
		try {
			// WebSocket server에 연결을 시도한다.
			boolean connected = wsClient.connectBlocking(m_reconnectTrialInterval.toMillis(), TimeUnit.MILLISECONDS);
			if ( connected ) {
				getLogger().info("connected to {}", wsClient.getURI());
				
				// WebSocket server에 연결된 경우 {@link WebSocketClient} 객체를 반환하고 loop를 종료시킨다
				return FOption.of(wsClient);
			}
		}
		catch ( Exception expected ) { }
		
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
