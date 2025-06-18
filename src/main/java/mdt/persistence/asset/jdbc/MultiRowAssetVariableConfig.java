package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.KeyValue;
import utils.func.FOption;
import utils.json.JacksonUtils;
import utils.stream.FStream;
import utils.stream.KeyValueFStream;

import mdt.ElementLocation;
import mdt.persistence.asset.AssetVariableConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiRowAssetVariableConfig extends AbstractJdbcAssetVariableConfig implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:jdbc:multi-row";

	private static final String FIELD_READ_QUERY = "readQuery";
	private static final String FIELD_UPDATE_QUERY = "updateQuery";
	private static final String FIELD_ROWS = "rows";
	private static final String FIELD_KEY = "key";
	private static final String FIELD_SUBPATH = "subPath";
	
	private String m_readQuery;
	@Nullable private String m_updateQuery;
	private Map<String,String> m_mapping;	// row key -> subpath mapping
	
	private MultiRowAssetVariableConfig()  { }
	public MultiRowAssetVariableConfig(ElementLocation elementLoc,
										@Nullable String jdbcConfigKey, @Nullable Duration validPeriod,
										String readQuery, @Nullable String updateQuery,
										Map<String,String> keyToSubpathMappings) {
		super(elementLoc, jdbcConfigKey, validPeriod);
		
		m_readQuery = readQuery;
		m_updateQuery = updateQuery;
		m_mapping = keyToSubpathMappings;
	}
	
	public String getReadQuery() {
		return m_readQuery;
	}
	
	public String getUpdateQuery() {
		return m_updateQuery;
	}
	
	public Map<String,String> getKeyToSubpathMapping() {
		return m_mapping;
	}

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}

	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);
		
		gen.writeStringField(FIELD_READ_QUERY, m_readQuery);
		FOption.acceptOrThrow(m_updateQuery, q -> gen.writeStringField(FIELD_UPDATE_QUERY, q));
		
		gen.writeArrayFieldStart(FIELD_ROWS);
		KeyValueFStream.from(this.m_mapping).forEachOrThrow(kv -> {
			gen.writeStartObject();
			gen.writeStringField(FIELD_KEY, kv.key());
			gen.writeStringField(FIELD_SUBPATH, kv.value());
			gen.writeEndObject();
		});
		gen.writeEndArray();
	}

	public static MultiRowAssetVariableConfig deserializeFields(JsonNode jnode) {
		MultiRowAssetVariableConfig config = new MultiRowAssetVariableConfig();
		config.loadFields(jnode);
		
		config.m_readQuery = FOption.ofNullable(jnode.get(FIELD_READ_QUERY))
									.map(JsonNode::asText)
									.getOrThrow(() -> new IllegalArgumentException("missing '" + FIELD_READ_QUERY + "' field"));
		config.m_updateQuery = FOption.map(jnode.get(FIELD_UPDATE_QUERY), JsonNode::asText);
		config.m_mapping = FStream.from(jnode.get("rows").elements())
									.mapToKeyValue(rowNode -> {
										String key = JacksonUtils.getStringField(rowNode, FIELD_KEY);
										String subPath = JacksonUtils.getStringField(rowNode, FIELD_SUBPATH);
										return KeyValue.of(key, subPath);
									})
									.toMap();
		
		return config;
	}
}
