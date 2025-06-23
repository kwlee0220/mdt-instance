package mdt.endpoint.ros2;

import java.time.Duration;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.endpoint.ros2.msg.Ros2MessageHandler;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class MDTRos2EndpointConfig {
	private String m_connectionConfig = "default";
	private Duration m_reconnectInterval = Duration.ofSeconds(10);
	private List<Ros2MessageHandler> m_messages;
}
