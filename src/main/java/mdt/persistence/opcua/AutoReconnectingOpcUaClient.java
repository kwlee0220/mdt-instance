package mdt.persistence.opcua;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import javax.annotation.concurrent.GuardedBy;

import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.api.UaClient;
import org.eclipse.milo.opcua.sdk.client.api.UaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractService;

import utils.LoggerSettable;
import utils.UnitUtils;
import utils.async.Guard;
import utils.func.FOption;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AutoReconnectingOpcUaClient extends AbstractService
										implements SessionActivityListener, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AutoReconnectingOpcUaClient.class);
	
	private final String m_serverEndpoint;
	private final Duration m_reconnectInterval;
	private Logger m_logger = s_logger;
	
	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private volatile OpcUaServerReconnect m_reconnect;
	@GuardedBy("m_guard") private volatile UaClient m_uaClient;
	
	protected void opcUaSessionConnected(UaClient client) throws Exception { }
	protected void opcUaSessionDisconnected() { }
	
	public AutoReconnectingOpcUaClient(String serverEndpoint, Duration reconnectInterval) {
		Preconditions.checkNotNull(serverEndpoint);
		Preconditions.checkNotNull(reconnectInterval);
		
		m_serverEndpoint = serverEndpoint;
		m_reconnectInterval = reconnectInterval;
	}
	
	public AutoReconnectingOpcUaClient(String serverEndpoint, String reconnectInterval) {
		this(serverEndpoint, UnitUtils.parseDuration(reconnectInterval));
	}

	/**
	 * MQTT Broker에 연결된 클라이언트를 반환한다. 연결이 되지 않은 경우에는 {@code null}을 반환한다.
	 * 
	 * @return	연결에 성공한 경우는 {@code MqttClient} 객체, 그렇지 않은 경우는 {@code null}.
	 */
	public UaClient pollOpcUaClient() {
		return m_uaClient;
	}
	
	/**
	 * MQTT Broker에 연결될 때까지 대기한다.
	 * <p>
	 * 만일 주어진 시간동안 연결되지 않으면 {@link TimeoutException}을 발생시킨다.
	 *
	 * @param timeout	제한 시간
	 * @return	연결된 {@code MqttClient} 객체
	 * @throws InterruptedException	연결 대기 중에 인터럽트가 발생한 경우
	 * @throws TimeoutException	제한 시간 내에 연결되지 않은 경우
	 */
	public UaClient waitOpcUaClient(Duration timeout) throws InterruptedException, TimeoutException {
		return m_guard.awaitCondition(() -> m_uaClient != null, timeout)
						.andGet(() -> m_uaClient);
	}
	
	/**
	 * MQTT Broker에 연결될 때까지 무한히 대기한다.
	 *
	 * @return	연결된 {@code MqttClient} 객체
	 * @throws InterruptedException	연결 대기 중에 인터럽트가 발생한 경우
	 */
	public UaClient waitOpcUaClient() throws InterruptedException {
		return m_guard.awaitCondition(() -> m_uaClient != null)
						.andGet(() -> m_uaClient);
	}

	@Override
    public void onSessionInactive(UaSession session) {
		m_guard.run(() -> m_uaClient = null);
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("OpcUaServer disconnected: server={}", m_serverEndpoint);
		}
		
		try {
			opcUaSessionDisconnected();
		}
		catch ( Throwable e ) {
			getLogger().warn("MqttBrokerDisconnection action was failed: cause={}", e);
		}
		
		// 재연결을 시도한다.
		tryConnect();
	}
	
	@Override
	public Logger getLogger() {
		return m_logger;
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = FOption.getOrElse(logger, s_logger);
	}

	@Override
	protected void doStart() {
		tryConnect();
	}

	@Override
	protected void doStop() { }
	
	private void tryConnect() {
		// MQTT Broker에 연결될 때까지 반복적으로 연결을 시도한다.
		m_reconnect = OpcUaServerReconnect.builder()
											.opcUaServerEndpoint(m_serverEndpoint)
											.activityListener(this)
											.reconnectTryInterval(m_reconnectInterval)
											.build();
		m_reconnect.setLogger(getLogger());
		m_reconnect.whenFinished(result -> {
			if ( result.isSuccessful() ) {
				if ( getLogger().isInfoEnabled() ) {
					getLogger().info("OpcUaServer connected: endpoint={}", m_serverEndpoint);
				}
				m_guard.run(() -> {
					m_uaClient = result.getUnchecked();
					m_reconnect = null;
				});
				
				try {
					opcUaSessionConnected(m_uaClient);
				}
				catch ( Exception e ) {
					s_logger.error("opcUaSessionConnection action has been failed: cause={}", e);
				}
			}
			else {
				m_guard.run(() -> m_uaClient = null);
			}
		});

		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("trying to connect to OpcUaServer: endpoint={}", m_serverEndpoint);
		}
		m_reconnect.start();
	}
	
	public static final void main(String... args) throws Exception {
		String endpoint = "opc.tcp://129.254.91.134:4840/mdt/panda";
		AutoReconnectingOpcUaClient reconnect = new AutoReconnectingOpcUaClient(endpoint, "5s");
		reconnect.startAsync();
		
		UaClient client = reconnect.waitOpcUaClient();
		System.out.println(client.toString());
	}
}
