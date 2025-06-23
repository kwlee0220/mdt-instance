package mdt.endpoint.ros2.msg;

import java.time.Instant;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.FaaastRuntime;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix = "m_")
public abstract class Ros2Message {
	private String m_op;
	private String m_topic;

	abstract public void update(FaaastRuntime rt, String smId, String elementPath, SubmodelElement buffer);
	abstract protected MsgPayload getPayload();
	
	public Instant getTimestamp() {
		return getPayload().getTimestamp();
	}
}
