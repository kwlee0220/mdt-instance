package mdt;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.experimental.UtilityClass;

import utils.KeyValue;
import utils.StrSubstitutor;
import utils.func.FOption;
import utils.io.IOUtils;
import utils.jdbc.JdbcConfiguration;
import utils.stream.FStream;

import mdt.client.support.MqttBrokerConfig;
import mdt.endpoint.ros2.RosBridgeConnectionConfig;
import mdt.ksx9101.GlobalPersistenceConfig;
import mdt.ksx9101.JpaConfiguration;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;
import mdt.persistence.asset.opcua.OpcUaConnectionConfig;

import de.fraunhofer.iosb.ilt.faaast.service.starter.InitializationException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@UtilityClass
public class MDTGlobalConfigurations {
	public static final String CONFIG_GROUP_JDBC = "jdbcs";
	public static final String CONFIG_GROUP_MQTT_BROKER = "mqttBrokers";
	public static final String CONFIG_GROUP_OPC_UA = "opcuas";
	public static final String CONFIG_GROUP_ROS_BRIDGE = "rosBridges";
	
	private static File s_globalConfigFile;
	
	@Accessors(prefix="s_")
	@Getter(lazy=true)
	private final static Set<Map.Entry<String, JsonNode>> s_properties = loadProperties();
	
	public static void setGlobalConfigFile(File globalConfigFile) {
		s_globalConfigFile = globalConfigFile;
	}
	
	public static <T> T getConfig(String configGroup, String configName, Class<T> cls)
		throws ResourceNotFoundException {
		JsonNode configNode = getConfigNode(configGroup, configName);
		
		try {
			JsonMapper mapper = MDTModelSerDe.getJsonMapper();
			return mapper.readValue(configNode.traverse(), cls);
		}
		catch ( IOException e ) {
			throw new InitializationException("Failed to read configuration: group="
														+ configGroup + ", name=" + configName, e);
		}
	}
	
	public static JdbcConfiguration getJdbcConfig(String configName) throws ResourceNotFoundException  {
		return getConfig(CONFIG_GROUP_JDBC, configName, JdbcConfiguration.class);
	}
	
	public static FOption<JpaConfiguration> loadJpaConfiguration() {
		Preconditions.checkState(s_globalConfigFile != null, "GlobalConfigurationFile has not been set");
		
		if ( !s_globalConfigFile.exists() ) {
			return FOption.empty();
		}
		
		try {
			return FOption.of(getConfig("persistence", "default", GlobalPersistenceConfig.class).getJpaConfig());
		}
		catch ( ResourceNotFoundException e ) {
			return FOption.empty();
		}
	}
	
	public static MqttBrokerConfig getMqttBrokerConfig(String configName) throws ResourceNotFoundException  {
		return getConfig(CONFIG_GROUP_MQTT_BROKER, configName, MqttBrokerConfig.class);
	}
	
	public static OpcUaConnectionConfig getOpcUaConfig(String configName) throws ResourceNotFoundException  {
		return getConfig(CONFIG_GROUP_OPC_UA, configName, OpcUaConnectionConfig.class);
	}
	
	public static RosBridgeConnectionConfig getRosBridgeConfig(String configName) throws ResourceNotFoundException  {
		return getConfig(CONFIG_GROUP_ROS_BRIDGE, configName, RosBridgeConnectionConfig.class);
	}
	
	private static JsonNode getConfigNode(String configGroup, String configKey) {
		JsonNode configGroupNode = findConfigGroup(configGroup);
		Map<String,JsonNode> configs = FStream.from(configGroupNode.fields())
												.mapToKeyValue(ent -> KeyValue.of(ent.getKey(), ent.getValue()))
												.toMap(Maps.newLinkedHashMap());
		
		JsonNode configNode = configs.get(configKey);
		if ( configNode != null ) {
			return configNode;
		}
		else if ( configKey.equalsIgnoreCase("default") ) {
			return configs.values().iterator().next();
		}
		else {
			throw new ResourceNotFoundException("Global configuration", String.format("key=%s.%s", configGroup, configKey));
		}
	}
	
	private static JsonNode findConfigGroup(String configKey) {
		Preconditions.checkState(s_globalConfigFile != null, "GlobalConfigurationFile has not been set");
		
		if ( !s_globalConfigFile.exists() ) {
			throw new IllegalStateException("Global configuration file not found: "
											+ s_globalConfigFile.getAbsolutePath());
		}
		
		return FStream.from(getProperties())
						.findFirst(ent -> ent.getKey().equals(configKey))
						.map(ent -> ent.getValue())
						.getOrThrow(() -> new ResourceNotFoundException("GlobalConfiguration", "key=" + configKey));
	}
	
	private static Set<Map.Entry<String, JsonNode>> loadProperties() {
		Preconditions.checkState(s_globalConfigFile != null, "GlobalConfigurationFile has not been set");
		
		if ( !s_globalConfigFile.exists() ) {
			throw new IllegalStateException("Global configuration file not found: "
											+ s_globalConfigFile.getAbsolutePath());
		}
		
		try {
			// 설정 파일을 미리 읽어서 variable substitution을 수행한다.
			String confJson = IOUtils.toString(s_globalConfigFile);
			confJson = new StrSubstitutor().replace(confJson);
			
			// 설정 파일을 tree 형태로 읽어 모든 속성 정보를 반환한다.
			JsonMapper mapper = MDTModelSerDe.getJsonMapper();
			return mapper.readTree(confJson).properties();
		}
		catch ( JsonMappingException e ) {
			String msg = String.format("Failed to parse global_configuration: file=%s, cause=%s",
										s_globalConfigFile.getAbsolutePath(), e);
			throw new IllegalStateException(msg);
		}
		catch ( IOException e ) {
			String msg = String.format("Failed to read global_configuration: file=%s, cause=%s",
										s_globalConfigFile.getAbsolutePath(), e);
			throw new InitializationException(msg);
		}
	}
}
