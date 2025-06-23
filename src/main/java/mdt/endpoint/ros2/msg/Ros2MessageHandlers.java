package mdt.endpoint.ros2.msg;

import java.io.IOException;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import lombok.experimental.UtilityClass;

import utils.json.JacksonDeserializationException;
import utils.json.JacksonUtils;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@UtilityClass
public class Ros2MessageHandlers {
	private static final String FIELD_TYPE = "messageType";
	
	@SuppressWarnings("serial")
	public static class Deserializer extends StdDeserializer<Ros2MessageHandler> {
		public Deserializer() {
			this(null);
		}
		public Deserializer(Class<?> vc) {
			super(vc);
		}
	
		@Override
		public Ros2MessageHandler deserialize(JsonParser parser, DeserializationContext ctxt)
			throws IOException, JacksonException {
			JsonNode jnode = parser.getCodec().readTree(parser);
			return parseTypedJsonNode(jnode);
		}
	}
	
	public static Ros2MessageHandler parseTypedJsonNode(JsonNode jnode) throws IOException {
		String type = JacksonUtils.getStringFieldOrNull(jnode, FIELD_TYPE);
		if ( type == null ) {
			throw new JacksonDeserializationException(String.format("'%s' field is missing: json=%s",
																	FIELD_TYPE, jnode));
		}
		
		switch ( type ) {
			case JointStateMessageHandler.SERIALIZATION_TYPE:
				return JointStateMessageHandler.deserializeFields(jnode);
			default:
				throw new JacksonDeserializationException("Unregistered Ros2MessageHandler type: " + type);
		}
	}
}
