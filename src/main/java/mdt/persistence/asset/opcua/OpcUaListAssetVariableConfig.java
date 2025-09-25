package mdt.persistence.asset.opcua;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.json.JacksonUtils;
import utils.stream.FStream;

import mdt.ElementLocation;
import mdt.persistence.asset.AbstractAssetVariableConfig;
import mdt.persistence.asset.AssetVariableConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OpcUaListAssetVariableConfig extends AbstractAssetVariableConfig implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:opcua:list";
	private static final String FIELD_IDENTIFIERS = "identifiers";
	private static final String FIELD_READABLE = "readable";
	
	private List<Integer> m_identifiers;
	private Boolean m_readable = null;
	
	private OpcUaListAssetVariableConfig() { }
	public OpcUaListAssetVariableConfig(ElementLocation elementLoc, @Nullable Duration validPeriod,
										List<Integer> identifiers) {
		super(elementLoc, validPeriod);
		
		m_identifiers = identifiers;
	}

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}
	
	public boolean isReadable() {
		return m_readable == null || m_readable;
	}
	
	public List<Integer> getIdentifierAll() {
		return m_identifiers;
	}
	
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);
		
		gen.writeArrayFieldStart(FIELD_IDENTIFIERS);
		for ( Integer identifier : m_identifiers ) {
			gen.writeNumber(identifier);
		}
		gen.writeEndArray();
	}
	
	/**
	 * JSON 노드로부터 {@link OpcUaListAssetVariableConfig} 객체를 생성한다.
	 * <p>
	 * 본 메소드는 {@link AssetVariableConfig.Deserializer}에서 호출된다.
	 * 
	 * @param jnode	JSON 노드
	 * @return	생성된 {@link OpcUaListAssetVariableConfig} 객체.
	 */
	public static OpcUaListAssetVariableConfig deserializeFields(JsonNode jnode) {
		OpcUaListAssetVariableConfig config = new OpcUaListAssetVariableConfig();
		config.loadFields(jnode);

		config.m_readable = JacksonUtils.getBooleanField(jnode, FIELD_READABLE, null);
		config.m_identifiers = FStream.from(JacksonUtils.getArrayFieldOrNull(jnode, FIELD_IDENTIFIERS))
										.map(JsonNode::asInt)
										.toList();
		
		return config;
	}
}
