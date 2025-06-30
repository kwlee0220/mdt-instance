package mdt.endpoint.ros2;

import java.net.ConnectException;
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
import com.google.common.util.concurrent.AbstractService;

import utils.LoggerSettable;
import utils.async.Guard;
import utils.func.FOption;
import utils.func.Unchecked;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AutoReconnectingWebSocketClient extends AbstractService implements LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AutoReconnectingWebSocketClient.class);
	
	private final URI m_serverUri;
	private final Duration m_reconnectInterval;
	private Logger m_logger = s_logger;

	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private WebSocketReconnector m_reconnector = null;
	@GuardedBy("m_guard") private WebSocketClient m_wsClient = null;
	@GuardedBy("m_guard") private final List<WebSocketClientListener> m_listeners = Lists.newArrayList();
		
	public AutoReconnectingWebSocketClient(@NonNull URI serverUri, @NonNull Duration reconnectInterval) {
		Preconditions.checkNotNull(serverUri);
		Preconditions.checkNotNull(reconnectInterval);
		
		m_serverUri = serverUri;
		m_reconnectInterval = reconnectInterval;
	}
	
	public void send() {
		
	}

	@Override
	protected void doStart() {
		startAutoReconnecting();
	}

	@Override
	protected void doStop() {
		m_guard.run(() -> {
			if ( m_reconnector != null ) {
				m_reconnector.cancel(true);
				m_reconnector = null;
			}
		});
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
	
	private void startAutoReconnecting() {
		m_guard.lock();
		try {
			Preconditions.checkState(m_reconnector == null, "WebSocketReconnector is already running: server=%s",
									m_serverUri);
			
			// WebSocket server에 연결될 때까지 반복적으로 연결을 시도한다.
			m_reconnector = new WebSocketReconnector(Ros2MessageConsumer::new, m_reconnectInterval);
			m_reconnector.setLogger(getLogger());
	
			getLogger().info("trying to connect to WebSocketServer: server={}", m_serverUri);
			m_reconnector.start();
		}
		finally {
			m_guard.unlock();
		}
	}

	public boolean isConnected() {
		return m_guard.get(() -> m_wsClient != null && m_wsClient.isOpen());
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
		m_guard.awaitCondition(() -> m_wsClient != null && m_wsClient.isOpen(), timeout).andGet(() -> null);
	}
	
	/**
	 * MQTT Broker에 연결될 때까지 무한히 대기한다.
	 *
	 * @return	연결된 {@code MqttClient} 객체
	 * @throws InterruptedException	연결 대기 중에 인터럽트가 발생한 경우
	 */
	public void awaitConnected() throws InterruptedException {
		m_guard.awaitCondition(() -> m_wsClient != null && m_wsClient.isOpen()).andGet(() -> null);
	}
	
	@Override
	public Logger getLogger() {
		return m_logger;
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = FOption.getOrElse(logger, s_logger);
	}
	
	private class Ros2MessageConsumer extends WebSocketClient {
		public Ros2MessageConsumer() {
			super(m_serverUri);
		}

		@Override
		public void onOpen(ServerHandshake handshakedata) {
			// startAutoReconnecting()가 호출이 끝이나서 m_state 상태가 CONNECTED 상태가 될 때까지 대기한다.
			m_guard.lock();
			try {
				m_reconnector = null;  // 연결 성공 후 재연결 시도 중지
				m_wsClient = this;  // 현재 WebSocketClient를 저장
				
				for ( WebSocketClientListener listener: m_listeners ) {
					Unchecked.runOrIgnore(() -> listener.onOpen(Ros2MessageConsumer.this, handshakedata));
				}
				m_logger.info("WebSocket connected: server={}", getURI());
			}
			finally {
				m_guard.unlock();
			}
		}

		@Override
		public void onMessage(String message) {
			m_guard.lock();
			try {
				for ( WebSocketClientListener listener: m_listeners ) {
					Unchecked.runOrIgnore(() -> listener.onMessage(Ros2MessageConsumer.this, message));
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
				if ( m_wsClient == null ) {
					return; // 이미 연결이 종료된 경우 무시
				}
				m_wsClient = null;  // 연결이 종료된 경우 WebSocketClient 객체를 null로 설정
				
				for ( WebSocketClientListener listener: m_listeners ) {
					Unchecked.runOrIgnore(() -> listener.onClose(Ros2MessageConsumer.this, code, reason, remote));
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
			if ( ex instanceof ConnectException ) {
				return;
			}
			m_guard.lock();
			try {
				for ( WebSocketClientListener listener: m_listeners ) {
					Unchecked.runOrIgnore(() -> listener.onError(Ros2MessageConsumer.this, ex));
				}
			}
			finally {
				m_guard.unlock();
			}
			m_logger.error("WebSocket error: server={}, cause={}", getURI(), ex);
		}
	}
}
