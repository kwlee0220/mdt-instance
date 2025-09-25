package mdt.persistence.asset;

import java.io.IOException;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import lombok.experimental.UtilityClass;

import utils.json.JacksonDeserializationException;
import utils.json.JacksonUtils;

import mdt.persistence.asset.jdbc.MultiColumnCollectionAssetVariableConfig;
import mdt.persistence.asset.jdbc.MultiRowAssetVariableConfig;
import mdt.persistence.asset.jdbc.SimpleJdbcAssetVariableConfig;
import mdt.persistence.asset.mqtt.MqttAssetVariableConfig;
import mdt.persistence.asset.opcua.OpcUaAssetVariableConfig;
import mdt.persistence.asset.opcua.OpcUaCollectionAssetVariableConfig;
import mdt.persistence.asset.opcua.OpcUaListAssetVariableConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@UtilityClass
public class AssetVariableConfigs {
	private static final String FIELD_TYPE = "@type";
	
	@SuppressWarnings("serial")
	public static class Serializer extends StdSerializer<AssetVariableConfig> {
		private Serializer() {
			this(null);
		}
		private Serializer(Class<AssetVariableConfig> cls) {
			super(cls);
		}
		
		@Override
		public void serialize(AssetVariableConfig config, JsonGenerator gen, SerializerProvider provider)
			throws IOException, JsonProcessingException {
			gen.writeStartObject();
			gen.writeStringField(FIELD_TYPE, config.getSerializationType());
			config.serializeFields(gen);
			gen.writeEndObject();
		}
	}

	@SuppressWarnings("serial")
	public static class Deserializer extends StdDeserializer<AssetVariableConfig> {
		public Deserializer() {
			this(null);
		}
		public Deserializer(Class<?> vc) {
			super(vc);
		}
	
		@Override
		public AssetVariableConfig deserialize(JsonParser parser, DeserializationContext ctxt)
			throws IOException, JacksonException {
			JsonNode jnode = parser.getCodec().readTree(parser);
			
			return parseJsonNode(jnode);
		}
	}
	
	
	public static AssetVariableConfig parseJsonNode(JsonNode jnode) throws IOException {
		String type = JacksonUtils.getStringFieldOrNull(jnode, FIELD_TYPE);
		if ( type == null ) {
			throw new JacksonDeserializationException(String.format("'%s' field is missing: json=%s",
																	FIELD_TYPE, jnode));
		}
		
		switch ( type ) {
			case SimpleJdbcAssetVariableConfig.SERIALIZATION_TYPE:
				return SimpleJdbcAssetVariableConfig.deserializeFields(jnode);
			case MultiColumnCollectionAssetVariableConfig.SERIALIZATION_TYPE:
				return MultiColumnCollectionAssetVariableConfig.deserializeFields(jnode);
			case MultiRowAssetVariableConfig.SERIALIZATION_TYPE:
				return MultiRowAssetVariableConfig.deserializeFields(jnode);
			case MqttAssetVariableConfig.SERIALIZATION_TYPE:
				return MqttAssetVariableConfig.deserializeFields(jnode);
			case OpcUaAssetVariableConfig.SERIALIZATION_TYPE:
				return OpcUaAssetVariableConfig.deserializeFields(jnode);
			case OpcUaListAssetVariableConfig.SERIALIZATION_TYPE:
				return OpcUaListAssetVariableConfig.deserializeFields(jnode);
			case OpcUaCollectionAssetVariableConfig.SERIALIZATION_TYPE:
				return OpcUaCollectionAssetVariableConfig.deserializeFields(jnode);
			default:
				throw new JacksonDeserializationException("Unregistered AssetVariableConfig type: " + type);
		}
	}
}
