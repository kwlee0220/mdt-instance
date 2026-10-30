package mdt.persistence.asset.robot;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ROS 메시지 header의 timestamp({@code sec}, {@code nanosec}).
 * <p>
 * RobotTwin 변수 값({@link RobotTwinVariable#getStamp()})의 원본 생성 시각을 담는다.
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
