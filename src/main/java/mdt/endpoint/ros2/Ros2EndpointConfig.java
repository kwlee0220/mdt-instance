package mdt.endpoint.ros2;

import java.time.Duration;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.stream.FStream;

import mdt.endpoint.ros2.msg.Ros2MessageHandler;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class Ros2EndpointConfig extends EndpointConfig<Ros2Endpoint> {
	private String m_connectionConfig = "default";
	private Duration m_reconnectInterval = Duration.ofSeconds(10);
	private List<Ros2MessageHandler<?>> m_messages;
	
	@Override
	public String toString() {
		List<String> handlers = FStream.from(m_messages).map(this::toRos2MessageHandlerString).toList();
		
		return String.format("Ros2EndpointConfig[connectionConfig=%s, reconnectInterval=%s, handlers=%s]",
							m_connectionConfig, m_reconnectInterval, handlers);
	}
	
	private String toRos2MessageHandlerString(Ros2MessageHandler<?> handler) {
		return String.format("%s=%s", handler.getTopic(), handler.getMessageType());
	}
}
