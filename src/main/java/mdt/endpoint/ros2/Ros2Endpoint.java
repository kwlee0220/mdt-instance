package mdt.endpoint.ros2;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.Maps;

import utils.stream.FStream;

import mdt.FaaastRuntime;
import mdt.MDTGlobalConfigurations;
import mdt.endpoint.ros2.msg.Ros2Message;
import mdt.endpoint.ros2.msg.Ros2MessageHandler;
import mdt.model.MDTModelSerDe;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class Ros2Endpoint implements Endpoint<Ros2EndpointConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(Ros2Endpoint.class);
	private static final String SUBSCRIBE_MSG_FORMAT = "{\"op\": \"subscribe\", \"topic\": \"%s\"}";

	private FaaastRuntime m_faaast;
	private Ros2EndpointConfig m_config;
	private RosBridgeConnectionConfig m_connConfig;
	private AutoReconnectingWebSocketClient m_ros2Client;
	private Map<String,Ros2MessageHandler<?>> m_handlers = Maps.newHashMap();
	
	private WebSocketClientListener m_wsCallback = new WebSocketClientListener() {
		@Override
		public void onOpen(ServerHandshake handshakedata) {
			for ( Ros2MessageHandler<?> handler : m_handlers.values() ) {
				String subscribeMsg = String.format(SUBSCRIBE_MSG_FORMAT, handler.getTopic());
				m_ros2Client.send(subscribeMsg);
				s_logger.info("[ROS2Endpoint] Subscribed to topic: {}, messageType={}",
								handler.getTopic(), handler.getMessageType());
			}
		}
		
		@Override
		public void onMessage(String message) throws Exception {
			try {
				JsonMapper mapper = MDTModelSerDe.getJsonMapper();
				JsonNode jnode = mapper.readTree(message);
				String topic = jnode.get("topic").asText();
				Ros2MessageHandler handler = m_handlers.get(topic);
				
				Ros2Message msg = handler.readMessage(message);
				handler.update(msg);
			}
			catch ( IOException e ) {
				s_logger.error("Failed to parse message: {}, cause={}", message, e);
			}
		}
		
		@Override public void onError(Exception ex) { }
		@Override public void onClose(int code, String reason, boolean remote) { }
	};

	@Override
	public void init(CoreConfig coreConfig, Ros2EndpointConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		m_config = config;
		
		m_faaast = new FaaastRuntime(serviceContext);
		for ( Ros2MessageHandler<?> handler: config.getMessages() ) {
			m_handlers.put(handler.getTopic(), handler);
		}
		
		try {
			m_connConfig = MDTGlobalConfigurations.getRosBridgeConnectionConfig(m_config.getConnectionConfig());

			URI uri = new URI(m_connConfig.getServerUri());
			m_ros2Client = new AutoReconnectingWebSocketClient(uri, m_config.getReconnectInterval());
			m_ros2Client.addListener(m_wsCallback);
		}
		catch ( Exception e ) {
			throw new ConfigurationInitializationException("Failed to read global configuration, cause=" + e);
		}
	}

	@Override
	public Ros2EndpointConfig asConfig() {
		return m_config;
	}
	
    @Override
    public void start() throws EndpointException {
		// 등록된 모든 메시지 핸들러에 대해 ElementLocation을 활성화한다.
    	FStream.from(m_handlers.values()).forEach(h -> h.initialize(m_faaast));
		
    	m_ros2Client.startAutoReconnecting();
    }

    @Override
    public void stop() {
    	m_ros2Client.close();
    }
	
	@Override
	public String toString() {
		return "ROS2Endpoint[conn=" + m_connConfig + "]";
	}
}
