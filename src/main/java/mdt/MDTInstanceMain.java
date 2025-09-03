package mdt;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.text.StringSubstitutor;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import utils.HomeDirPicocliCommand;
import utils.LogbackConfigLoader;
import utils.Throwables;
import utils.func.FOption;
import utils.io.IOUtils;

import mdt.assetconnection.MDTAssetConnectionConfig;
import mdt.assetconnection.operation.HttpOperationProviderConfig;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.assetconnection.operation.MDTOperationProviderConfig;
import mdt.assetconnection.operation.ProgramOperationProviderConfig;
import mdt.config.AASOperationConfig.HttpOperationConfig;
import mdt.config.AASOperationConfig.JavaOperationConfig;
import mdt.config.AASOperationConfig.ProgramOperationConfig;
import mdt.config.MDTInstanceConfig;
import mdt.endpoint.MDTManagerHealthMonitorConfig;
import mdt.endpoint.mqtt.MqttEndpointConfig;
import mdt.endpoint.reconnector.MDTManagerReconnectorConfig;
import mdt.endpoint.ros2.Ros2EndpointConfig;
import mdt.model.MDTModelSerDe;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.PersistenceStackConfig;

import ch.qos.logback.classic.Level;
import de.fraunhofer.iosb.ilt.faaast.service.Service;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionConfig;
import de.fraunhofer.iosb.ilt.faaast.service.config.CertificateConfig;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.config.ServiceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.http.HttpEndpointConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.InvalidConfigurationException;
import de.fraunhofer.iosb.ilt.faaast.service.filestorage.memory.FileStorageInMemoryConfig;
import de.fraunhofer.iosb.ilt.faaast.service.messagebus.internal.MessageBusInternalConfig;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.memory.PersistenceInMemoryConfig;
import de.fraunhofer.iosb.ilt.faaast.service.starter.InitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.starter.cli.LogLevelTypeConverter;
import de.fraunhofer.iosb.ilt.faaast.service.starter.logging.FaaastFilter;
import de.fraunhofer.iosb.ilt.faaast.service.starter.model.EndpointType;
import de.fraunhofer.iosb.ilt.faaast.service.starter.util.ServiceConfigHelper;
import de.fraunhofer.iosb.ilt.faaast.service.util.ImplementationManager;
import de.fraunhofer.iosb.ilt.faaast.service.util.LambdaExceptionHelper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Command(name="mdt",
	parameterListHeading = "Parameters:%n",
	optionListHeading = "Options:%n",
	mixinStandardHelpOptions = true,
	description="%nManufactoring DigitalTwin (MDT) related commands."
)
public class MDTInstanceMain extends HomeDirPicocliCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(MDTInstanceMain.class);
    private static final String DEFAULT_HEARTBEAT_INTERVAL = "60s";
    private static final String DEFAULT_MANAGER_CHECK_INTERVAL = "10s";
    private static final String DEFAULT_GLOBAL_CONFIG_FILE = "mdt_global_config.json";
    private static final String DEFAULT_CERT_FILE = "mdt_cert.p12";
    private static final String ENV_MDT_INSTANCE_ENDPOINT = "MDT_INSTANCE_ENDPOINT";
    private static final String ENV_MDT_MANAGER_ENDPOINT = "MDT_MANAGER_ENDPOINT";
    private static final String ENV_MDT_GLOBAL_CONFIG_FILE = "MDT_GLOBAL_CONFIG_FILE";
    private static final String ENV_MDT_KEY_STORE_FILE = "MDT_KEY_STORE_FILE";
    private static final String ENV_MDT_KEY_STORE_PASSWORD = "MDT_KEY_STORE_PASSWORD";
    
    protected static final String ENV_PATH_SEPARATOR = ".";
    protected static final String ENV_PATH_ALTERNATIVE_SEPARATOR = "_";
    protected static final String ENV_KEY_PREFIX = "faaast";
    protected static final String ENV_PATH_LOGLEVEL_EXTERNAL = envPath(ENV_KEY_PREFIX, "loglevel_external");
    protected static final String ENV_PATH_LOGLEVEL_FAAAAST = envPath(ENV_KEY_PREFIX, "loglevel_faaast");
    protected static final AtomicReference<Service> serviceRef = new AtomicReference<>();
    private static final ObjectMapper mapper = new ObjectMapper()
										            .enable(SerializationFeature.INDENT_OUTPUT)
										            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
										            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    
	@Option(names={"--config", "-c"}, paramLabel="configFile", defaultValue="config.json",
			description={"The config file path. Default Value = config.json"})
	private Path m_confFile;
	
	@Option(names={"--id"}, paramLabel="id", description="MDTInstance id")
	private String m_id;
	
	public static enum InstanceType { JAR, EXTERNAL };
	@Option(names={"--type", "-t"}, paramLabel="type", defaultValue = "jar",
				description="attach type: ${COMPLETION-CANDIDATES}")
	private InstanceType m_type;
	
	@Option(names={"--instanceEndpoint"}, paramLabel="endpoint", description="endpoint to this MDTInstance")
	private String m_instanceEndpoint;
	
	@Option(names={"--managerEndpoint"}, paramLabel="endpoint", description="MDTManager endpoint")
	private String m_managerEndpoint;

    @Option(names = "--globalConfig", paramLabel="path", description = "global configuration file path")
    private File m_globalConfigFile;

    @Option(names = "--keyStore", paramLabel="path", description = "path to KeyStore file")
    private File m_keyStoreFile;
	
	@Option(names={"--model", "-m"}, paramLabel="modelFile", defaultValue="model.json",
			description={"Asset Administration Shell Environment FilePath. Default Value = model.json"})
	private Path m_initModel;

    @Option(names = "--logbackConfig", paramLabel="path", description = "path to Logback config file")
    private File m_logbackConfFile;
	
	@Option(names={"--loglevel-external"}, paramLabel="<logLevelExternal>",
			description={"Sets the log level for external packages. "
						+ "This overrides the log level defined by other commands such as -q or -v."})
	private Level m_logLevelExternal;
	
	@Option(names={"--loglevel-faaast"}, paramLabel="<logLevelFaaast>",
			description={"Sets the log level for FA³ST packages. This overrides the log level defined by "
						+ "other commands such as -q or -v."})
	private Level m_logLevelFaaast;
	
	@Option(names={"--quite", "-q"},
			description={"Reduces log output (ERROR for FA³ST packages, ERROR for all other packages). "
						+ "Default information about the starting process will still be printed."})
	private boolean quite;
	
	@Option(names={"--verbose", "-v"},
			description={"Enables verbose logging (INFO for FA³ST packages and all other packages)."})
	private boolean verbose;

	public static final void main(String... args) throws Exception {
		MDTInstanceMain app = new MDTInstanceMain();
		CommandLine commandLine = new CommandLine(app)
									.registerConverter(Level.class, new LogLevelTypeConverter())
									.setCaseInsensitiveEnumValuesAllowed(true)
									.setUsageHelpWidth(110);
		try {
			commandLine.parseArgs(args);

			if ( commandLine.isUsageHelpRequested() ) {
				commandLine.usage(System.out, Ansi.OFF);
			}
			else {
				app.run();
			}
		}
		catch ( Throwable e ) {
			System.err.println(e);
			commandLine.usage(System.out, Ansi.OFF);
		}
	}

	@Override
	protected void run(Path homeDir) throws Exception {
		if ( m_logbackConfFile != null ) {
			LogbackConfigLoader.loadLogbackConfigFromFile(m_logbackConfFile);
		}
		
		//
		// setting configuration for MDTInstance
		//
		if ( !m_confFile.isAbsolute() ) {
			m_confFile = homeDir.resolve(m_confFile);
		}
		if ( !m_initModel.isAbsolute() ) {
			m_initModel = homeDir.resolve(m_initModel);
		}
		
		// 설정 파일을 미리 읽어서 variable substitution을 수행한다.
		String confJson = IOUtils.toString(m_confFile.toFile());
		StringSubstitutor interpolator = StringSubstitutor.createInterpolator();
		confJson = interpolator.replace(confJson);
    	
        MDTInstanceConfig conf = JsonMapper.builder()
											.findAndAddModules()
											.addModule(new JavaTimeModule())
											.build()
											.readerFor(MDTInstanceConfig.class)
											.readValue(confJson);
		if ( m_id != null ) {
			conf.setId(m_id);
		}
		if ( m_instanceEndpoint != null ) {
			conf.setInstanceEndpoint(m_instanceEndpoint);
		}
		else if ( System.getenv(ENV_MDT_INSTANCE_ENDPOINT) != null ) {
			conf.setInstanceEndpoint(System.getenv(ENV_MDT_INSTANCE_ENDPOINT));
		}
		if ( m_managerEndpoint != null ) {
			conf.setManagerEndpoint(m_managerEndpoint);
		}
		else if ( System.getenv(ENV_MDT_MANAGER_ENDPOINT) != null ) {
			conf.setInstanceEndpoint(System.getenv(ENV_MDT_MANAGER_ENDPOINT));
		}
		
		if ( m_globalConfigFile != null ) {
			if ( !m_globalConfigFile.exists() ) {
				String msg = String.format("GlobalConfig file does not exist: %s", m_globalConfigFile);
				throw new InitializationException(msg);
			}
			conf.setGlobalConfigFile(m_globalConfigFile);
		}
		else {
			String globalConfigFilePath = System.getenv(ENV_MDT_GLOBAL_CONFIG_FILE);
			if ( globalConfigFilePath != null && new File(globalConfigFilePath).exists() ) {
				conf.setGlobalConfigFile(new File(globalConfigFilePath));
			}
			else if ( conf.getGlobalConfigFile() == null ) {
				File globalConfigFile = homeDir.resolve(DEFAULT_GLOBAL_CONFIG_FILE).toFile();
				getLogger().warn("GlobalConfig file not specified, using default: {}",
									globalConfigFile.getAbsolutePath());
				conf.setGlobalConfigFile(globalConfigFile);
			}
		}
		
		if ( m_keyStoreFile != null ) {
			if ( !m_keyStoreFile.exists() ) {
				String msg = String.format("Keystore file does not exist: %s", m_keyStoreFile);
				throw new InitializationException(msg);
			}
			conf.setKeyStoreFile(m_keyStoreFile);
		}
		else {
			String keyStoreFilePath = System.getenv(ENV_MDT_KEY_STORE_FILE);
			if ( keyStoreFilePath != null && new File(keyStoreFilePath).exists() ) {
				conf.setKeyStoreFile(new File(keyStoreFilePath));
			}
			else if ( conf.getKeyStoreFile() == null ) {
				File keyStoreFile = homeDir.resolve(DEFAULT_CERT_FILE).toFile();
				getLogger().warn("Keystore file not specified, using default: {}", keyStoreFile.getAbsolutePath());
				conf.setKeyStoreFile(keyStoreFile);
			}
		}
		
		configureLogging();
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("MDTInstance Configuration:");
			getLogger().info("\tMDTInstance id: {}",  conf.getId());
			getLogger().info("\tInitial model: " + m_initModel);
			getLogger().info("\tConfiguration file: " + m_confFile);
			getLogger().info("\tMDTInstance endpoint: {}", conf.getInstanceEndpoint());
			getLogger().info("\tMDTManager endpoint: {}", conf.getManagerEndpoint());
			getLogger().info("\tMDTGlobalConfig file: {}", conf.getGlobalConfigFile());
			getLogger().info("\tKeyStore: {}", conf.getKeyStoreFile());
		}
		
		MDTGlobalConfigurations.setGlobalConfigFile(conf.getGlobalConfigFile());
		
		// Initial model을 읽어서 MDTModelLookup를 생성한다.
    	loadMDTModelLookup(m_initModel.toFile());
		
		ImplementationManager.init();
		ServiceConfig config = null;
		try {
			config = toServiceConfig(conf);
		}
		catch ( IOException e ) {
			getLogger().error("Failed to load ServiceConfig, file={}, cause={}", m_confFile, ""+e);
			throw new InitializationException("Error loading config file", e);
		}
		config = ServiceConfigHelper.autoComplete(config);
//        config = withModel(config);
		config = withEndpoints(config);
//        config = withOverrides(config);
//        validate(config);
		runService(config);
		
		Map<String,String> parts = extractHostAndPort(conf.getInstanceEndpoint());
		if ( m_type == InstanceType.JAR ) {
			System.out.println("[***MARKER***] MDTInstance started: " + conf.getInstanceEndpoint());
		}
	}
	
	private MDTModelLookup loadMDTModelLookup(File initModel) throws IOException {
    	Environment env = MDTModelSerDe.readValue(initModel, Environment.class);
    	try {
			return MDTModelLookup.getInstanceOrCreate(env.getSubmodels());
		}
		catch ( ConfigurationInitializationException e ) {
			throw new IOException("Failed to load MDT Model Lookup", e);
		}
	}

    private ServiceConfig toServiceConfig(MDTInstanceConfig instConf) throws IOException {
		CoreConfig coreConfig = CoreConfig.builder()
											.requestHandlerThreadPoolSize(2)
											.build();
		MessageBusInternalConfig msgBusConfig = MessageBusInternalConfig.builder().build();
		FileStorageInMemoryConfig fsConfig = FileStorageInMemoryConfig.builder().build();
		
		return ServiceConfig.builder()
							.core(coreConfig)
							.persistence(loadPersistenceConfig(instConf))
							.endpoints(loadEndpointConfigs(instConf))
							.messageBus(msgBusConfig)
							.fileStorage(fsConfig)
							.assetConnections(loadAssetConnectionConfigs(instConf))
							.build();
    }
    
    private static final String KEYSTORE_TYPE = "PKCS12";
	
	@SuppressWarnings("rawtypes")
	private List<EndpointConfig> loadEndpointConfigs(MDTInstanceConfig instConf) {
		Preconditions.checkArgument(instConf.getManagerEndpoint() != null,
										"MDTInstanceManager endpoint not specified in the configuration");
		Preconditions.checkArgument(instConf.getInstanceEndpoint() != null,
										"MDTInstance endpoint not specified in the configuration");
		
		Map<String,String> parts = extractHostAndPort(instConf.getInstanceEndpoint());
		int port = Integer.parseInt(parts.get("port"));
		
		List<EndpointConfig> configs = Lists.newArrayList();

		if ( instConf.getKeyStorePassword() == null && instConf.getKeyPassword() == null ) {
			String keyStorePassword = System.getenv(ENV_MDT_KEY_STORE_PASSWORD);
			if ( keyStorePassword != null && !keyStorePassword.isBlank() ) {
				instConf.setKeyStorePassword(keyStorePassword);
			}
			else {
				throw new IllegalArgumentException(
						"Keystore or key password not specified in the configuration");
			}
		}
		CertificateConfig certConfig = CertificateConfig.builder()
														.keyStoreType(KEYSTORE_TYPE)
														.keyStorePath(instConf.getKeyStoreFile())
														.keyStorePassword(instConf.getKeyStorePassword())
														.keyPassword(instConf.getKeyPassword())
														.build();
		certConfig.setKeyAlias("server-key");
		HttpEndpointConfig httpEpConfig = HttpEndpointConfig.builder()
															.port(port)
															.sni(false)
															.certificate(certConfig)
															.build();
		configs.add(httpEpConfig);
		
		if ( m_type == InstanceType.EXTERNAL ) { 
			String heartbeatInterval = FOption.getOrElse(instConf.getHeartbeatInterval(),
															DEFAULT_HEARTBEAT_INTERVAL);
			Preconditions.checkArgument(instConf.getId() != null,
											"MDTInstance's id is not specified in the configuration");
			MDTManagerReconnectorConfig reconnectConfig
								= new MDTManagerReconnectorConfig(instConf.getId(), instConf.getManagerEndpoint(),
																	instConf.getInstanceEndpoint(), heartbeatInterval,
																	true);
			configs.add(reconnectConfig);
		}
		else if ( m_type == InstanceType.JAR ) {
			String mgrHealthCheckInterval = FOption.getOrElse(instConf.getManagerCheckInterval(),
																DEFAULT_MANAGER_CHECK_INTERVAL);
			MDTManagerHealthMonitorConfig mgrHealthMonitorConfig
												= new MDTManagerHealthMonitorConfig(instConf.getManagerEndpoint(),
																					mgrHealthCheckInterval, true);
			configs.add(mgrHealthMonitorConfig);
		}
		else {
			throw new IllegalArgumentException("Unknown instance type: " + m_type);
		}
		
		FOption.accept(instConf.getMdtEndpoints(), conf -> {
			FOption.accept(conf.getMqtt(), mqttConf -> {
				MqttEndpointConfig c = new MqttEndpointConfig();
				c.setMqttConfig(mqttConf.getMqttConfig());
				c.setSubscribers(mqttConf.getSubscribers());
				configs.add(c);
			});

			FOption.accept(conf.getRos2(), rosConf -> {
				Ros2EndpointConfig c = new Ros2EndpointConfig();
				c.setConnectionConfig(rosConf.getConnectionConfig());
				c.setMessages(rosConf.getMessages());
				configs.add(c);
			});
		});
		
		return configs;
	}
	
	private PersistenceConfig<?> loadPersistenceConfig(MDTInstanceConfig instConf) {
		PersistenceConfig topConfig = PersistenceInMemoryConfig.builder()
																.initialModelFile(m_initModel.toFile())
																.build();
		for ( int i = instConf.getPersistenceStacks().size() - 1; i >= 0; --i ) {
			PersistenceStackConfig<?> stackConfig = instConf.getPersistenceStacks().get(i);
			stackConfig.setBasePersistenceConfig(topConfig);
			topConfig = stackConfig;
		}
		
		return topConfig;
	}
	
	@SuppressWarnings("rawtypes")
	private List<AssetConnectionConfig> loadAssetConnectionConfigs(MDTInstanceConfig instConf) {
		Map<Reference,MDTOperationProviderConfig> opProviders = Maps.newHashMap();
		
		if ( instConf.getOperations() != null ) {
			for ( ProgramOperationConfig conf: instConf.getOperations().getProgramOperations() ) {
				Reference opRef = conf.getOperationIdentifier().toReference();
				MDTOperationProviderConfig opProviderConfig = new MDTOperationProviderConfig();
				opProviderConfig.setProgram(new ProgramOperationProviderConfig(conf.getDescriptorFile()));
				opProviders.put(opRef, opProviderConfig);
			}
			for ( JavaOperationConfig conf: instConf.getOperations().getJavaOperations() ) {
				Reference opRef = conf.getOperationIdentifier().toReference();
				MDTOperationProviderConfig opProviderConfig = new MDTOperationProviderConfig();
				opProviderConfig.setJava(new JavaOperationProviderConfig(conf.getClassName(), Map.of()));
				opProviders.put(opRef, opProviderConfig);
			}
			for ( HttpOperationConfig conf: instConf.getOperations().getHttpOperations() ) {
				Reference opRef = conf.getOperationIdentifier().toReference();
				MDTOperationProviderConfig opProviderConfig = new MDTOperationProviderConfig();
				HttpOperationProviderConfig httpConf
										= new HttpOperationProviderConfig(conf.getEndpoint(), conf.getOpId(),
																		conf.getPollInterval(), conf.getTimeout());
				opProviderConfig.setHttp(httpConf);
				opProviders.put(opRef, opProviderConfig);
			}
		}
		
		MDTAssetConnectionConfig assetConConfig = new MDTAssetConnectionConfig();
		assetConConfig.setOperationProviders(opProviders);
		
		return List.of(assetConConfig);
	}
	
    private void configureLogging() {
		FaaastFilter.setLevelFaaast(Level.WARN);
		FaaastFilter.setLevelExternal(Level.WARN);
		
		if ( verbose ) {
			FaaastFilter.setLevelFaaast(Level.INFO);
			FaaastFilter.setLevelExternal(Level.INFO);
		}
		else if ( quite ) {
			FaaastFilter.setLevelFaaast(Level.ERROR);
			FaaastFilter.setLevelExternal(Level.ERROR);
		}
		if ( m_logLevelFaaast != null ) {
			FaaastFilter.setLevelFaaast(m_logLevelFaaast);
		}
		if ( getEnvValue(ENV_PATH_LOGLEVEL_FAAAAST) != null && !getEnvValue(ENV_PATH_LOGLEVEL_FAAAAST).isBlank() ) {
			FaaastFilter.setLevelFaaast(
					Level.toLevel(getEnvValue(ENV_PATH_LOGLEVEL_FAAAAST), FaaastFilter.getLevelFaaast()));
		}
		if ( m_logLevelExternal != null ) {
			FaaastFilter.setLevelExternal(m_logLevelExternal);
		}
		if ( getEnvValue(ENV_PATH_LOGLEVEL_EXTERNAL) != null && !getEnvValue(ENV_PATH_LOGLEVEL_EXTERNAL).isBlank() ) {
			FaaastFilter.setLevelExternal(
					Level.toLevel(getEnvValue(ENV_PATH_LOGLEVEL_EXTERNAL), FaaastFilter.getLevelExternal()));
		}
		LOGGER.info("Using log level for FA³ST packages: {}", FaaastFilter.getLevelFaaast());
		LOGGER.info("Using log level for external packages: {}", FaaastFilter.getLevelExternal());
    }
    
    protected static String envPathWithAlternativeSeparator(String key) {
        return key.replace(ENV_PATH_SEPARATOR, ENV_PATH_ALTERNATIVE_SEPARATOR);
    }
    
    private static String envPath(String... args) {
        return Stream.of(args).collect(Collectors.joining(ENV_PATH_SEPARATOR));
    }

    private static String getEnvValue(String key) {
        return System.getenv().containsKey(key)
                ? System.getenv(key)
                : System.getenv(envPathWithAlternativeSeparator(key));
    }
    
	private static Map<String, String> extractHostAndPort(String url) {
		Map<String, String> result = Maps.newHashMap();

		try {
			URI uri = new URI(url);
			String host = uri.getHost();
			int port = uri.getPort();

			if ( host != null ) {
				result.put("host", host);
			}

			if ( port != -1 ) {
				result.put("port", String.valueOf(port));
			}

			return result;
		}
		catch ( URISyntaxException e ) {
			LOGGER.error("Failed to parse URL: {}", url, e);
			return result;
		}
	}


    private void printEndpointInfo(ServiceConfig config) {
        if (LOGGER.isInfoEnabled()) {
            config.getEndpoints().stream().forEach(x -> {
                if (HttpEndpointConfig.class.isAssignableFrom(x.getClass())) {
                    LOGGER.info("HTTP endpoint available on port {}", ((HttpEndpointConfig) x).getPort());
                }
//                else if (OpcUaEndpointConfig.class.isAssignableFrom(x.getClass())) {
//                    LOGGER.info("OPC UA endpoint available on port {}", ((OpcUaEndpointConfig) x).getTcpPort());
//                }
            });
        }
    }
    
    private ServiceConfig withEndpoints(ServiceConfig config) {
        try {
            ServiceConfigHelper.apply(config, List.<EndpointType>of().stream()
                    .map(LambdaExceptionHelper.rethrowFunction(
                            x -> x.getImplementation().getDeclaredConstructor().newInstance()))
                    .collect(Collectors.toList()));
            return config;
        }
        catch (InvalidConfigurationException | ReflectiveOperationException e) {
            throw new InitializationException("Adding endpoints to config failed", e);
        }
    }
    private void runService(ServiceConfig config) {
		try {
			serviceRef.set(new Service(config));
			LOGGER.info("Starting FA³ST Service...");
			LOGGER.debug("Using configuration file: ");
			printConfig(config);
			serviceRef.get().start();
			LOGGER.info("FA³ST Service successfully started");
			printEndpointInfo(config);

			LOGGER.info("Press CTRL + C to stop");
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			if ( cause instanceof EndpointException epe ) {
				cause = epe.getCause();
			}
			LOGGER.error("Unexpected exception encountered while executing FA³ST Service, cause=" + cause);
			throw new RuntimeException(cause);
		}
    }
    private void printConfig(ServiceConfig config) {
        if (LOGGER.isDebugEnabled()) {
            try {
                LOGGER.debug(mapper.writeValueAsString(config));
            }
            catch (JsonProcessingException e) {
                LOGGER.debug("Printing config failed", e);
            }
        }
    }
}
