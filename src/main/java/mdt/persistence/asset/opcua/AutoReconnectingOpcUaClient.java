package mdt.persistence.asset.opcua;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import javax.annotation.concurrent.GuardedBy;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.api.UaClient;
import org.eclipse.milo.opcua.sdk.client.api.UaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractService;

import utils.LoggerSettable;
import utils.UnitUtils;
import utils.thread.Guard;
import utils.func.Optionals;


/**
 * OPC-UA 서버와의 연결을 유지하는 서비스.
 * <p>
 * 서비스가 시작되면 {@link OpcUaServerReconnect}로 서버에 연결될 때까지 주기적으로 연결을 시도하고,
 * 연결된 세션이 끊기면 끊긴 클라이언트를 정리한 뒤 다시 연결을 시도한다. 서비스를 중지하면
 * 진행 중인 연결 시도를 취소하고 연결된 클라이언트를 끊는다.
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
	 * OPC-UA 서버에 연결된 클라이언트를 반환한다. 연결이 되지 않은 경우에는 {@code null}을 반환한다.
	 *
	 * @return	연결에 성공한 경우는 {@code UaClient} 객체, 그렇지 않은 경우는 {@code null}.
	 */
	public UaClient pollOpcUaClient() {
		return m_uaClient;
	}

	/**
	 * OPC-UA 서버에 연결될 때까지 대기한다.
	 * <p>
	 * 만일 주어진 시간동안 연결되지 않으면 {@link TimeoutException}을 발생시킨다.
	 *
	 * @param timeout	제한 시간
	 * @return	연결된 {@code UaClient} 객체
	 * @throws InterruptedException	연결 대기 중에 인터럽트가 발생한 경우
	 * @throws TimeoutException	제한 시간 내에 연결되지 않은 경우
	 */
	public UaClient waitOpcUaClient(Duration timeout) throws InterruptedException, TimeoutException {
		return m_guard.awaitCondition(() -> m_uaClient != null, timeout)
						.andGet(() -> m_uaClient);
	}

	/**
	 * OPC-UA 서버에 연결될 때까지 무한히 대기한다.
	 *
	 * @return	연결된 {@code UaClient} 객체
	 * @throws InterruptedException	연결 대기 중에 인터럽트가 발생한 경우
	 */
	public UaClient waitOpcUaClient() throws InterruptedException {
		return m_guard.awaitCondition(() -> m_uaClient != null)
						.andGet(() -> m_uaClient);
	}

	@Override
    public void onSessionInactive(UaSession session) {
		// 끊긴 클라이언트를 꺼낸다. 이미 꺼냈으면(중복 이벤트, 또는 중지 중) 더 할 일이 없다.
		UaClient lost = m_guard.get(() -> {
			UaClient client = m_uaClient;
			m_uaClient = null;
			return client;
		});
		if ( lost == null ) {
			return;
		}
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("OpcUaServer disconnected: server={}", m_serverEndpoint);
		}

		// Milo 클라이언트는 끊긴 뒤에도 스스로 재연결을 계속 시도하므로, 정리하지 않고 새 클라이언트를
		// 만들면 끊길 때마다 클라이언트와 그 쓰레드·소켓이 쌓인다. 따라서 끊긴 클라이언트를 먼저 정리한다.
		disconnectQuietly(lost);

		try {
			opcUaSessionDisconnected();
		}
		catch ( Throwable e ) {
			getLogger().warn("opcUaSessionDisconnected action was failed: cause={}", ""+e);
		}

		// 서비스가 실행 중인 경우에만 재연결을 시도한다.
		if ( isRunning() ) {
			tryConnect();
		}
	}

	@Override
	public Logger getLogger() {
		return Optionals.getOrElse(m_logger, s_logger);
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = logger;
	}

	@Override
	protected void doStart() {
		try {
			tryConnect();

			// 연결은 백그라운드에서 재시도하므로 연결을 기다리지 않고 바로 RUNNING 상태로 전이한다.
			// (연결된 클라이언트는 waitOpcUaClient()로 기다린다)
			notifyStarted();
		}
		catch ( Throwable e ) {
			notifyFailed(e);
		}
	}

	@Override
	protected void doStop() {
		OpcUaServerReconnect reconnect;
		UaClient client;
		m_guard.lock();
		try {
			reconnect = m_reconnect;
			m_reconnect = null;
			client = m_uaClient;
			m_uaClient = null;
		}
		finally {
			m_guard.signalAll();
			m_guard.unlock();
		}

		if ( reconnect != null ) {
			reconnect.cancel(true);
		}
		if ( client != null ) {
			disconnectQuietly(client);
		}
		notifyStopped();
	}

	private void tryConnect() {
		OpcUaServerReconnect reconnect = OpcUaServerReconnect.builder()
															.opcUaServerEndpoint(m_serverEndpoint)
															.activityListener(this)
															.reconnectTryInterval(m_reconnectInterval)
															.build();
		reconnect.setLogger(getLogger());

		// 이미 연결을 시도 중이면 새로 시작하지 않는다.
		boolean started = m_guard.get(() -> {
			if ( m_reconnect != null ) {
				return false;
			}
			m_reconnect = reconnect;
			return true;
		});
		if ( !started ) {
			return;
		}

		reconnect.whenFinished(result -> {
			if ( result.isSuccessful() ) {
				UaClient client = result.getUnchecked();

				// 연결이 끝나기 전에 서비스가 중지되었으면 새 클라이언트를 채택하지 않고 정리한다.
				boolean adopted = m_guard.get(() -> {
					if ( m_reconnect == reconnect ) {
						m_reconnect = null;
					}
					if ( state() == State.STOPPING || state() == State.TERMINATED || state() == State.FAILED ) {
						return false;
					}
					m_uaClient = client;
					return true;
				});
				if ( !adopted ) {
					disconnectQuietly(client);
					return;
				}

				if ( getLogger().isInfoEnabled() ) {
					getLogger().info("OpcUaServer connected: endpoint={}", m_serverEndpoint);
				}
				try {
					opcUaSessionConnected(client);
				}
				catch ( Exception e ) {
					getLogger().error("opcUaSessionConnected action has been failed: cause={}", ""+e);
				}
			}
			else {
				// 연결 시도는 인터럽트·취소(서비스 중지)로만 끝나며, 연결 실패는 계속 재시도한다.
				m_guard.run(() -> {
					if ( m_reconnect == reconnect ) {
						m_reconnect = null;
					}
				});
				if ( isRunning() ) {
					getLogger().warn("OpcUaServer reconnection has stopped unexpectedly: endpoint={}, result={}",
									m_serverEndpoint, result);
				}
			}
		});

		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("trying to connect to OpcUaServer: endpoint={}", m_serverEndpoint);
		}
		reconnect.start();
	}

	private void disconnectQuietly(UaClient client) {
		try {
			// 정리 과정의 세션 이벤트로 재연결이 다시 시작되지 않도록 listener를 먼저 제거한다.
			if ( client instanceof OpcUaClient opcUaClient ) {
				opcUaClient.removeSessionActivityListener(this);
			}
			client.disconnect();
		}
		catch ( Throwable e ) {
			getLogger().warn("failed to disconnect OpcUaClient: endpoint={}, cause={}", m_serverEndpoint, ""+e);
		}
	}

	public static final void main(String... args) throws Exception {
		String endpoint = "opc.tcp://129.254.91.134:4840/mdt/panda";
		AutoReconnectingOpcUaClient reconnect = new AutoReconnectingOpcUaClient(endpoint, "5s");
		reconnect.startAsync();

		UaClient client = reconnect.waitOpcUaClient();
		System.out.println(client.toString());
	}
}
