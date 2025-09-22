package mdt.endpoint.ros2.msg;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import mdt.FaaastRuntime;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="messageType")
@JsonSubTypes({
	@JsonSubTypes.Type(value=JointStateMessageHandler.class, name="joint_states"),
})
public interface Ros2MessageHandler<T extends Ros2Message> {
	public String getTopic();
	
	public Ros2Message readMessage(String message) throws IOException;
	
	public void initialize(FaaastRuntime faaast);
	public void update(T message);
}
