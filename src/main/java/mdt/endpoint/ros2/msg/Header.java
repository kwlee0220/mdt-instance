package mdt.endpoint.ros2.msg;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class Header {
	private final Stamp m_stamp;
	private final String m_frameId;
	
	public Header(@JsonProperty("stamp") Stamp stamp, @JsonProperty("frame_id") String frameId) {
		m_stamp = stamp;
		m_frameId = frameId;
	}
	
	public Stamp getStamp() {
		return m_stamp;
	}
	
	public String getFrameId() {
		return m_frameId;
	}
}