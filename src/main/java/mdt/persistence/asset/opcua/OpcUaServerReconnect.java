package mdt.persistence.asset.opcua;

import java.time.Duration;
import java.util.concurrent.CancellationException;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.api.UaClient;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import utils.Throwables;
import utils.async.AbstractPeriodicPoller;
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
public class OpcUaServerReconnect extends AbstractPeriodicPoller<UaClient> {
	private static final Logger s_logger = LoggerFactory.getLogger(OpcUaServerReconnect.class);
	
	private final String m_endpointUrl;
	private final @Nullable SessionActivityListener m_listener;
	
	private OpcUaServerReconnect(Builder builder) {
		super(builder.m_reconnectTryInterval);
		
		Preconditions.checkNotNull(builder.m_opcUaServerEndpoint);
		
		m_endpointUrl = builder.m_opcUaServerEndpoint;
		m_listener = builder.m_activityListener;
		
		setLogger(s_logger);
	}

	@Override
	protected FOption<UaClient> tryPoll() throws InterruptedException, CancellationException {
		getLogger().debug("trying connection to {}", m_endpointUrl);

		OpcUaClient client = null;
		try {
			// OPC-UA 서버에 연결을 시도한다.
			client = OpcUaClient.create(m_endpointUrl);
			if ( m_listener != null ) {
				client.addSessionActivityListener(m_listener);
			}
			client.connect().get();
			getLogger().info("connected to OpcUaServer: endpoint={}", m_endpointUrl);

			// 연결된 경우 클라이언트 객체를 반환하고 loop를 종료시킨다.
			return FOption.of(client);
		}
		catch ( InterruptedException | CancellationException e ) {
			disconnectQuietly(client);
			throw e;
		}
		catch ( Exception e ) {
			// 서버 탐색 실패는 UaException으로, 세션 생성·활성화 실패는 ExecutionException으로 온다.
			// 어느 경우든 만들다 만 클라이언트를 정리하고 FOption.empty()를 반환하여
			// 다음 주기에 다시 시도한다(예외를 던지면 poller가 실패로 끝나 재연결이 멈춘다).
			getLogger().debug("failed to connect to OpcUaServer: endpoint={}, cause={}",
								m_endpointUrl, "" + Throwables.unwrapThrowable(e));
			disconnectQuietly(client);

			return FOption.empty();
		}
	}

	private void disconnectQuietly(@Nullable OpcUaClient client) {
		if ( client != null ) {
			// 정리 과정의 세션 이벤트로 재연결이 중복 시작되지 않도록 listener를 먼저 제거한다.
			if ( m_listener != null ) {
				client.removeSessionActivityListener(m_listener);
			}
			client.disconnect();
		}
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
