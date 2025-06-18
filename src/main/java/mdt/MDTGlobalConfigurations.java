package mdt;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.apache.commons.text.StringSubstitutor;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import lombok.experimental.UtilityClass;

import utils.KeyValue;
import utils.func.FOption;
import utils.io.IOUtils;
import utils.jdbc.JdbcConfiguration;
import utils.stream.FStream;

import mdt.ksx9101.GlobalPersistenceConfig;
import mdt.ksx9101.JpaConfiguration;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;
import mdt.persistence.mqtt.MqttBrokerConfig;
import mdt.persistence.opcua.OpcUaServerConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@UtilityClass
public class MDTGlobalConfigurations {
//	private static final String ENVVAR_MDT_GLOBAL_CONFIG = "MDT_GLOBAL_CONFIG_FILE";
//	private static final File DEFAULT_MDT_GLOBAL_CONFIG = new File(MDTInstanceManager.GLOBAL_CONF_FILE_NAME);
	private static File s_globalConfigFile;
	
	public static void setGlobalConfigFile(File globalConfigFile) {
		s_globalConfigFile = globalConfigFile;
	}
	
	public static JdbcConfiguration getJdbcConfig(String key) throws IOException, ResourceNotFoundException  {
		JsonNode configNode = findConfigNode("jdbcConfigs", key);
		
		JsonMapper mapper = MDTModelSerDe.getJsonMapper();
		return mapper.readValue(configNode.traverse(), JdbcConfiguration.class);
	}
	
	public static FOption<JpaConfiguration> loadJpaConfiguration() {
		Preconditions.checkState(s_globalConfigFile != null, "GlobalConfigurationFile has not been set");
		
		if ( !s_globalConfigFile.exists() ) {
			return FOption.empty();
		}
		
		try {
			// "persistent" key에 해당하는 설정 정보를 반환한다.
			//
			
			// 설정 파일을 tree 형태로 읽어 "persistent"에 해당하는 노드를 찾는다
			// 찾은 sub-node를 주어진 class를 기준으로 다시 read하여 configuration 객체를 생성한다.
			JsonMapper mapper = MDTModelSerDe.getJsonMapper();
			return  FStream.from(mapper.readTree(s_globalConfigFile).properties())
							.findFirst(ent -> ent.getKey().equals("persistence"))
							.mapOrThrow(ent -> mapper.readValue(ent.getValue().traverse(),
																GlobalPersistenceConfig.class))
							.map(GlobalPersistenceConfig::getJpaConfig);
		}
		catch ( JsonMappingException e ) {
			String msg = String.format("Failed to parse global_configuration: file=%s, cause=%s",
										s_globalConfigFile.getAbsolutePath(), e);
			throw new IllegalStateException(msg);
		}
		catch ( IOException e ) {
			String msg = String.format("Failed to read global_configuration: file=%s, cause=%s",
										s_globalConfigFile.getAbsolutePath(), e);
			throw new IllegalStateException(msg);
		}
	}
	
	public static MqttBrokerConfig getMqttConfig(String name) throws IOException  {
		JsonNode configNode = findConfigNode("mqttBrokers", name);
		
		JsonMapper mapper = MDTModelSerDe.getJsonMapper();
		return mapper.readValue(configNode.traverse(), MqttBrokerConfig.class);
	}
	
	public static OpcUaServerConfig getOpcUaConfig(String name) throws IOException  {
		JsonNode configNode = findConfigNode("opcUaServers", name);
		
		JsonMapper mapper = MDTModelSerDe.getJsonMapper();
		return mapper.readValue(configNode.traverse(), OpcUaServerConfig.class);
	}
	
	private static JsonNode findConfigGroup(String configKey) throws IOException {
		Preconditions.checkState(s_globalConfigFile != null, "GlobalConfigurationFile has not been set");
		
		if ( !s_globalConfigFile.exists() ) {
			throw new IllegalStateException("Global configuration file not found: "
											+ s_globalConfigFile.getAbsolutePath());
		}
		
		try {
			// "persistent" key에 해당하는 설정 정보를 반환한다.
			//
			
			// 설정 파일을 미리 읽어서 variable substitution을 수행한다.
			String confJson = IOUtils.toString(s_globalConfigFile);
			StringSubstitutor interpolator = StringSubstitutor.createInterpolator();
			confJson = interpolator.replace(confJson);
			
			// 설정 파일을 tree 형태로 읽어 "persistent"에 해당하는 노드를 찾는다
			// 찾은 sub-node를 주어진 class를 기준으로 다시 read하여 configuration 객체를 생성한다.
			JsonMapper mapper = MDTModelSerDe.getJsonMapper();
			return FStream.from(mapper.readTree(confJson).properties())
							.findFirst(ent -> ent.getKey().equals(configKey))
							.map(ent -> ent.getValue())
							.getOrThrow(() -> new ResourceNotFoundException("GlobalConfiguration", "key=" + configKey));
		}
		catch ( JsonMappingException e ) {
			String msg = String.format("Failed to find global_configuration: file=%s, key=%s, cause=%s",
										s_globalConfigFile.getAbsolutePath(), configKey, e);
			throw new IllegalStateException(msg);
		}
	}
	
	private static JsonNode findConfigNode(String groupKey, String key) throws IOException {
		JsonNode configGroupNode = findConfigGroup(groupKey);
		Map<String,JsonNode> configs = FStream.from(configGroupNode.fields())
												.mapToKeyValue(ent -> KeyValue.of(ent.getKey(), ent.getValue()))
												.toMap(Maps.newLinkedHashMap());
		
		JsonNode configNode = configs.get(key);
		if ( configNode != null ) {
			return configNode;
		}
		else if ( key.equalsIgnoreCase("default") ) {
			return configs.values().iterator().next();
		}
		else {
			throw new ResourceNotFoundException("Global configuration", String.format("key=%s.%s", groupKey, key));
		}
	}
}
