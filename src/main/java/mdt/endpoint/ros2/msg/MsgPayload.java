package mdt.endpoint.ros2.msg;

import java.time.Instant;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class MsgPayload {
	private Header m_header;
	
	public Instant getTimestamp() {
		long sec = m_header.getStamp().getSec();
		long nanoSec = m_header.getStamp().getNanosec();
		return Instant.ofEpochSecond(sec, nanoSec);
	}
}
