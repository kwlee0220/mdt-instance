package mdt.endpoint.ros2.msg;

import java.io.IOException;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import mdt.FaaastRuntime;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
//@JsonSerialize(using = Ros2MessageHandlers.Serializer.class)
@JsonDeserialize(using = Ros2MessageHandlers.Deserializer.class)
public interface Ros2MessageHandler<T extends Ros2Message> {
	public String getTopic();
	public String getMessageType();
	
	public Ros2Message readMessage(String message) throws IOException;
	
	public void initialize(FaaastRuntime faaast);
	public void update(T message);
}
