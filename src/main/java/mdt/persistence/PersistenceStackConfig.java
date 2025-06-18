package mdt.persistence;

import java.io.File;
import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import utils.Utilities;
import utils.json.JacksonUtils;

import mdt.persistence.PersistenceStackConfig.Deserializer;
import mdt.persistence.PersistenceStackConfig.Serializer;

import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.memory.PersistenceInMemoryConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonSerialize(using = Serializer.class)
@JsonDeserialize(using = Deserializer.class)
public abstract class PersistenceStackConfig<P extends PersistenceStack> extends PersistenceConfig<P> {
	private PersistenceConfig<?> m_baseConfig;
	
	protected abstract void serializeFields(JsonGenerator gen) throws IOException;
	protected abstract void deserializeFields(JsonNode jnode) throws IOException;
	
	@JsonProperty("basePersistence")
	public PersistenceConfig<?> getBasePersistenceConfig() {
		return m_baseConfig;
	}

	@JsonProperty("basePersistence")
	public void setBasePersistenceConfig(PersistenceConfig<?> config) {
		if ( config instanceof PersistenceInMemoryConfig ) {
			((PersistenceInMemoryConfig) config).setInitialModelFile(new File("model.json"));
		}
		
		m_baseConfig = config;
	}
    
    @Override
	public String toString() {
		return String.format("%s: model=%s", getClass().getName(), getInitialModelFile());
	}

	protected static final String FIELD_TYPE = "@class";
	@SuppressWarnings("serial")
	public static class Serializer extends StdSerializer<PersistenceStackConfig> {
		private Serializer() {
			this(null);
		}
		private Serializer(Class<PersistenceStackConfig> cls) {
			super(cls);
		}
		
		@Override
		public void serialize(PersistenceStackConfig pstackConf, JsonGenerator gen, SerializerProvider provider)
			throws IOException, JsonProcessingException {
			gen.writeStartObject();
			gen.writeStringField(FIELD_TYPE, pstackConf.getClass().getName());
			pstackConf.serializeFields(gen);
			gen.writeEndObject();
		}
	}

	@SuppressWarnings("serial")
	public static class Deserializer extends StdDeserializer<PersistenceStackConfig> {
		public Deserializer() {
			this(null);
		}
		public Deserializer(Class<?> vc) {
			super(vc);
		}
	
		@Override
		public PersistenceStackConfig deserialize(JsonParser parser, DeserializationContext ctxt)
			throws IOException, JacksonException {
			JsonNode jnode = parser.getCodec().readTree(parser);
			String className = JacksonUtils.getStringField(jnode, FIELD_TYPE) + "Config";
			PersistenceStackConfig conf = (PersistenceStackConfig)Utilities.newInstance(className);
			conf.deserializeFields(jnode);
			return conf;
		}
	}
}
