package mdt.endpoint.ros2;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.Delegate;

import utils.stream.FStream;

import mdt.endpoint.ros2.msg.Ros2MessageHandler;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "connectionConfig", "reconnectInterval", "messages" })
public class Ros2EndpointConfig extends EndpointConfig<Ros2Endpoint> {
	@Delegate private final MDTConfig m_conf;
	
	public Ros2EndpointConfig() {
		m_conf = new MDTConfig();
	}
	
	public Ros2EndpointConfig(MDTConfig conf) {
		m_conf = conf;
	}
	
	@Override
	public String toString() {
		List<String> handlers = FStream.from(getMessages()).map(this::toRos2MessageHandlerString).toList();
		
		return String.format("Ros2EndpointConfig[connectionConfig=%s, reconnectInterval=%s, handlers=%s]",
							getConnectionConfig(), getReconnectInterval(), handlers);
	}
	
	private String toRos2MessageHandlerString(Ros2MessageHandler<?> handler) {
//		return String.format("%s=%s", handler.getTopic(), handler.getMessageType());
		return String.format("%s=%s", handler.getTopic(), handler.getClass().getSimpleName());
	}

	@Getter @Setter
	@Accessors(prefix="m_")
	public static class MDTConfig {
		private String m_connectionConfig = "default";
		private Duration m_reconnectInterval = Duration.ofSeconds(10);
		private List<Ros2MessageHandler<?>> m_messages;
	}
}
