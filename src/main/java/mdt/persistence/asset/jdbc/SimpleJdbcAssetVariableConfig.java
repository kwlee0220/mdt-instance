package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.time.Duration;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.func.FOption;
import utils.json.JacksonUtils;

import mdt.ElementLocation;
import mdt.persistence.asset.AssetVariableConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SimpleJdbcAssetVariableConfig extends AbstractJdbcAssetVariableConfig implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:jdbc:simple";
	static final String FIELD_READ_QUERY = "readQuery";
	static final String FIELD_UPDATE_QUERY = "updateQuery";
	
	private String m_readQuery;
	private @Nullable String m_updateQuery;
	
	private SimpleJdbcAssetVariableConfig()  { }
	public SimpleJdbcAssetVariableConfig(ElementLocation elementLoc, @Nullable String jdbcConfigKey,
											@Nullable Duration validPeriod,
											String readQuery, @Nullable String updateQuery) {
		super(elementLoc, jdbcConfigKey, validPeriod);
		
		m_readQuery = readQuery;
		m_updateQuery = updateQuery;
	}
	
	public String getReadQuery() {
		return m_readQuery;
	}
	
	public String getUpdateQuery() {
		return m_updateQuery;
	}

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}
	
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {	
		super.serializeFields(gen);
		gen.writeStringField(FIELD_READ_QUERY, m_readQuery);
		FOption.acceptOrThrow(m_updateQuery, sql -> gen.writeStringField(FIELD_UPDATE_QUERY, sql));
	}
	
	/**
	 * JSON 노드로부터 {@link SimpleJdbcAssetVariableConfig} 객체를 생성한다.
	 * <p>
	 * 본 메소드는 {@link AssetVariableConfig.Deserializer}에서 호출된다.
	 * 
	 * @param jnode	JSON 노드
	 * @return	생성된 {@link SimpleJdbcAssetVariableConfig} 객체.
	 * @throws IOException 
	 */
	public static SimpleJdbcAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		SimpleJdbcAssetVariableConfig config = new SimpleJdbcAssetVariableConfig();
		config.loadFields(jnode);
		
		config.m_readQuery = JacksonUtils.getStringField(jnode, FIELD_READ_QUERY);
		config.m_updateQuery = JacksonUtils.getStringFieldOrNull(jnode, FIELD_UPDATE_QUERY);
		
		return config;
	}
}
