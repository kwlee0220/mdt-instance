package mdt.endpoint.ros2;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class RosBridgeConnectionConfig {
	private String m_serverUri;
	
	@Override
	public String toString() {
		return String.format("%s: server=%s",
							getClass().getSimpleName(), m_serverUri);
	}
}
