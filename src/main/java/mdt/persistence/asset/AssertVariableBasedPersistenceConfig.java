package mdt.persistence.asset;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import mdt.persistence.PersistenceStackConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AssertVariableBasedPersistenceConfig extends PersistenceStackConfig<AssertVariableBasedPersistence> {
	private final List<AssetVariableConfig> m_assetVariableConfigs;
	
	public AssertVariableBasedPersistenceConfig() {
		m_assetVariableConfigs = Lists.newArrayList();
	}
	
	public AssertVariableBasedPersistenceConfig(List<AssetVariableConfig> configs) {
		Preconditions.checkArgument(configs != null, "AssetVariableConfig list is null");
		
		m_assetVariableConfigs = configs;
	}
	
	@JsonProperty("assetVariables")
	public List<AssetVariableConfig> getAssetVariableConfigs() {
		return m_assetVariableConfigs;
	}

	@JsonProperty("assetVariables")
	public void setAssetVariableConfigs(List<AssetVariableConfig> configs) {
		Preconditions.checkArgument(configs != null, "AssetVariableConfig list is null");

		m_assetVariableConfigs.clear();
		m_assetVariableConfigs.addAll(configs);
	}

	@Override
	protected void serializeFields(JsonGenerator gen) throws IOException {
		gen.writeArrayFieldStart("assetVariableConfigs");
		for ( AssetVariableConfig varConf: m_assetVariableConfigs ) {
			gen.writeObject(varConf);
		}
		gen.writeEndArray();
	}

	@Override
	protected void deserializeFields(JsonNode jnode) throws IOException {
		JsonNode varsNode = jnode.get("assetVariables");
		if ( varsNode == null || !varsNode.isArray() ) {
			throw new IOException("assetVariableConfigs must be an array");
		}
		
		ObjectMapper mapper = new ObjectMapper();
		Iterator<JsonNode> iter = varsNode.elements();
		while ( iter.hasNext() ) {
			JsonNode varConfNode = iter.next();
			m_assetVariableConfigs.add(mapper.treeToValue(varConfNode, AssetVariableConfig.class));
		}
	}
}
