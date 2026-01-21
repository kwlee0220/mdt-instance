package mdt.persistence.asset.opcua;

import java.io.IOException;
import java.time.Duration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import javax.annotation.Nullable;

import utils.func.Optionals;
import utils.json.JacksonUtils;

import mdt.ElementLocation;
import mdt.persistence.asset.AbstractAssetVariableConfig;
import mdt.persistence.asset.AssetVariableConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OpcUaAssetVariableConfig extends AbstractAssetVariableConfig implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:opcua:simple";
	private static final String FIELD_NODE_PATH = "nodePath";
	private static final String FIELD_READABLE = "readable";
	
//	private String m_serverEndpoint;
	private String m_nodePath;
	private Boolean m_readable = null;
	
	private OpcUaAssetVariableConfig() { }
	public OpcUaAssetVariableConfig(ElementLocation elementLoc, @Nullable Duration validPeriod,
									String nodePath) {
		super(elementLoc, validPeriod);
		
//		m_serverEndpoint = serverEndpoint;
		m_nodePath = nodePath;
	}

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}
	
	public boolean isReadable() {
		return m_readable == null || m_readable;
	}
	
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);
//		gen.writeStringField("serverEndpoint", m_serverEndpoint);
		gen.writeStringField("nodePath", m_nodePath);
		Optionals.acceptThrow(m_readable, f -> gen.writeBooleanField(FIELD_NODE_PATH, f));
	}
	
	/**
	 * JSON 노드로부터 {@link OpcUaAssetVariableConfig} 객체를 생성한다.
	 * 
	 * @param jnode	JSON 노드
	 * @return	생성된 {@link OpcUaAssetVariableConfig} 객체.
	 */
	public static OpcUaAssetVariableConfig deserializeFields(JsonNode jnode) {
		OpcUaAssetVariableConfig config = new OpcUaAssetVariableConfig();
		config.loadFields(jnode);
		
//		config.m_serverEndpoint = JacksonUtils.getStringField(jnode, FIELD_SERVER_ENDPOINT);
		config.m_nodePath = JacksonUtils.getStringField(jnode, FIELD_NODE_PATH);
		config.m_readable = JacksonUtils.getBooleanField(jnode, FIELD_READABLE, null);
		
		return config;
	}
}
