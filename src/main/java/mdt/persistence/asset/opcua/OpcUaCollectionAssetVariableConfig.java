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
public class OpcUaCollectionAssetVariableConfig extends AbstractAssetVariableConfig implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:opcua:collection";
	private static final String FIELD_MAPPINGS = "mappings";
	private static final String FIELD_READABLE = "readable";
	
	private String m_folder;
	private Boolean m_readable = null;
	private List<SubPathToOpcUaId> m_mappings;	// subpath -> opcua identifier mapping
	
	public static record SubPathToOpcUaId(String subPath, int opcuaId) { }
	
	private OpcUaCollectionAssetVariableConfig() { }
	public OpcUaCollectionAssetVariableConfig(ElementLocation elementLoc, @Nullable Duration validPeriod,
												List<SubPathToOpcUaId> mappings) {
		super(elementLoc, validPeriod);
		
		m_mappings = mappings;
	}

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}
	
	public boolean isReadable() {
		return m_readable == null || m_readable;
	}
	
	public String getFolder() {
		return m_folder;
	}
	
	public List<SubPathToOpcUaId> getFieldMappings() {
		return m_mappings;
	}
	
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);
		
		gen.writeObjectFieldStart(FIELD_MAPPINGS);
		for ( SubPathToOpcUaId mapping : m_mappings ) {
			gen.writeNumberField(mapping.subPath(), mapping.opcuaId());
		}
		gen.writeEndObject();
	}
	
	/**
	 * JSON 노드로부터 {@link OpcUaCollectionAssetVariableConfig} 객체를 생성한다.
	 * <p>
	 * 본 메소드는 {@link AssetVariableConfig.Deserializer}에서 호출된다.
	 * 
	 * @param jnode	JSON 노드
	 * @return	생성된 {@link OpcUaCollectionAssetVariableConfig} 객체.
	 */
	public static OpcUaCollectionAssetVariableConfig deserializeFields(JsonNode jnode) {
		OpcUaCollectionAssetVariableConfig config = new OpcUaCollectionAssetVariableConfig();
		config.loadFields(jnode);

		config.m_readable = JacksonUtils.getBooleanField(jnode, FIELD_READABLE, null);
		config.m_mappings = FStream.from(jnode.get(FIELD_MAPPINGS).fields())
									.map(ent -> {
										String subPath = ent.getKey();
										int identifier = ent.getValue().asInt();
										return new SubPathToOpcUaId(subPath, identifier);
									})
									.toList();
		
		return config;
	}
	
	@Override
	public String toString() {
		String mappingsStr = FStream.from(m_mappings)
									.map(mapping -> String.format("%s:%d", mapping.subPath, mapping.opcuaId()))
									.join(", ", "{", "}");
		return String.format("%s=%s", getElementLocation(), mappingsStr);
	}
}
