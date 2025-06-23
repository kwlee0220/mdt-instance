package mdt.endpoint.ros2;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.annotation.concurrent.GuardedBy;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import utils.LoggerSettable;
import utils.async.Guard;
import utils.func.FOption;
import utils.func.Unchecked;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AutoReconnectingWebSocketClient extends WebSocketClient implements LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AutoReconnectingWebSocketClient.class);
	
	private final Duration m_reconnectInterval;
	private Logger m_logger = s_logger;

	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private boolean m_connected = false;
	@GuardedBy("m_guard") private final List<WebSocketClientListener> m_listeners = Lists.newArrayList();
		
	public AutoReconnectingWebSocketClient(@NonNull URI serverUri, @NonNull Duration reconnectInterval) {
		super(serverUri);
		
		Preconditions.checkNotNull(serverUri);
		Preconditions.checkNotNull(reconnectInterval);
		
		m_reconnectInterval = reconnectInterval;
	}
	
	public void addListener(@NonNull WebSocketClientListener listener) {
		Preconditions.checkArgument(listener != null);

		m_guard.lock();
		try {
			m_listeners.add(listener);
		}
		finally {
			m_guard.unlock();
		}
	}
	
	public void startAutoReconnecting() {
		// WebSocket server에 연결될 때까지 반복적으로 연결을 시도한다.
		WebSocketReconnect reconnect = WebSocketReconnect.builder()
														.webSocketClient(this)
														.reconnectTryInterval(m_reconnectInterval)
														.build();
		reconnect.setLogger(getLogger());

		getLogger().info("trying to connect to WebSocketServer: server={}", getURI());
		reconnect.start();
	}
	
	@Override
	public void onOpen(ServerHandshake handshakedata) {
		m_guard.lock();
		try {
			m_connected = true;
			for ( WebSocketClientListener listener: m_listeners ) {
				Unchecked.runOrIgnore(() -> listener.onOpen(handshakedata));
			}
		}
		finally {
			m_guard.unlock();
		}
		m_logger.info("WebSocket connected: server={}", getURI());
	}

	@Override
	public void onMessage(String message) {
		m_guard.lock();
		try {
			for ( WebSocketClientListener listener: m_listeners ) {
				Unchecked.runOrIgnore(() -> listener.onMessage(message));
			}
		}
		finally {
			m_guard.unlock();
		}
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
		m_guard.lock();
		try {
			m_connected = false;
			for ( WebSocketClientListener listener: m_listeners ) {
				Unchecked.runOrIgnore(() -> listener.onClose(code, reason, remote));
			}
			m_logger.info("WebSocket disconnected: server={}, code={}, reason={}, remote={}",
							getURI(), code, reason, remote);
			if ( remote ) {
				startAutoReconnecting();  // 원격 서버에 의해 연결이 종료된 경우 재연결 시도
			}
		}
		finally {
			m_guard.unlock();
		}
	}

	@Override
	public void onError(Exception ex) {
		m_guard.lock();
		try {
			for ( WebSocketClientListener listener: m_listeners ) {
				Unchecked.runOrIgnore(() -> listener.onError(ex));
			}
		}
		finally {
			m_guard.unlock();
		}
		m_logger.error("WebSocket error: server={}, cause={}", getURI(), ex);
	}

	public boolean isConnected() {
		return m_guard.get(() -> m_connected);
	}
	
	/**
	 * WebSocket server에 연결될 때까지 대기한다.
	 * <p>
	 * 만일 주어진 시간동안 연결되지 않으면 {@link TimeoutException}을 발생시킨다.
	 *
	 * @param timeout	제한 시간
	 * @return	연결된 {@code MqttClient} 객체
	 * @throws InterruptedException	연결 대기 중에 인터럽트가 발생한 경우
	 * @throws TimeoutException	제한 시간 내에 연결되지 않은 경우
	 */
	public void awaitConnected(Duration timeout) throws InterruptedException, TimeoutException {
		m_guard.awaitCondition(() -> m_connected, timeout).andGet(() -> null);
	}
	
	/**
	 * MQTT Broker에 연결될 때까지 무한히 대기한다.
	 *
	 * @return	연결된 {@code MqttClient} 객체
	 * @throws InterruptedException	연결 대기 중에 인터럽트가 발생한 경우
	 */
	public void awaitConnected() throws InterruptedException {
		m_guard.awaitCondition(() -> m_connected).andGet(() -> null);
	}
	
	@Override
	public Logger getLogger() {
		return m_logger;
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = FOption.getOrElse(logger, s_logger);
	}
}
