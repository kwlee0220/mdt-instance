package mdt.endpoint.ros2.msg;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class Stamp {
	private final long m_sec;
	private final long m_nanosec;
	
	public Stamp(@JsonProperty("sec") long sec, @JsonProperty("nanosec") long nanosec) {
		m_sec = sec;
		m_nanosec = nanosec;
	}
	
	public long getSec() {
		return m_sec;
	}

	public long getNanosec() {
		return m_nanosec;
	}
}
