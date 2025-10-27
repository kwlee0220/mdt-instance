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
import org.apache.commons.text.lookup.StringLookup;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import utils.HomeDirPicocliCommand;
import utils.LogbackConfigLoader;
import utils.Throwables;
import utils.func.FOption;
import utils.io.FileUtils;
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
import mdt.config.MDTServiceConfig;
import mdt.config.MDTService;
import mdt.endpoint.MDTManagerHealthMonitorConfig;
import mdt.endpoint.companion.ProgramCompanionConfig;
import mdt.endpoint.mqtt.MqttEndpointConfig;
import mdt.endpoint.reconnector.MDTManagerReconnectorConfig;
import mdt.endpoint.ros2.Ros2EndpointConfig;
import mdt.model.MDTModelSerDe;
import mdt.persistence.ConcurrentPersistenceConfig;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.MDTPersistenceStackConfig;
import mdt.persistence.asset.AssertVariableBasedPersistenceConfig;
import mdt.persistence.timeseries.TimeSeriesPersistenceStackConfig;

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
    private static final String ENV_MDT_MANAGER_ENDPOINT = "MDT_ENDPOINT";
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
	
	@Option(names={"--port", "-p"}, paramLabel="number", description="port number for MDTInstance")
	private Integer m_port;
	
	@Option(names={"--instanceEndpoint"}, paramLabel="endpoint", description="endpoint to this MDTInstance")
	private String m_instanceEndpoint;
	
	@Option(names={"--managerEndpoint"}, paramLabel="endpoint", description="MDTManager endpoint")
	private String m_managerEndpoint;

    @Option(names = "--globalConfig", paramLabel="path", description = "global configuration file path")
    private File m_globalConfigFile;

    @Option(names = "--keyStore", paramLabel="path", description = "path to KeyStore file")
    private File m_keyStoreFile;

    @Option(names = "--keyStorePassword", paramLabel="password", description = "password to KeyStore file")
    private String m_keyStorePassword;
	
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
	
	private MDTInstanceConfig m_mdtInstanceConfig;
	private Map<String,String> m_instanceVariables;
	
	public MDTInstanceConfig getMdtInstanceConfig() {
		Preconditions.checkState(m_mdtInstanceConfig != null, "MDTInstance configuration not initialized yet");
		return m_mdtInstanceConfig;
	}
	
	public Map<String,String> getInstanceVariables() {
		Preconditions.checkState(m_instanceVariables != null, "MDTInstance variables not initialized yet");
		return m_instanceVariables;
	}

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
    	
        MDTInstanceConfig mdtInstanceConfig = MDTModelSerDe.MAPPER
															.readerFor(MDTInstanceConfig.class)
															.readValue(confJson);
		if ( m_id != null ) {
			mdtInstanceConfig.setId(m_id);
		}
		Preconditions.checkArgument(mdtInstanceConfig.getId() != null, "MDTInstance id not specified");
		
		 // command-line 인자로 port가 지정된 경우 설정 파일의 port 설정을 override한다.
		
		if ( m_instanceEndpoint != null ) {
			mdtInstanceConfig.setInstanceEndpoint(m_instanceEndpoint);
		}
		else if ( System.getenv(ENV_MDT_INSTANCE_ENDPOINT) != null ) {
			mdtInstanceConfig.setInstanceEndpoint(System.getenv(ENV_MDT_INSTANCE_ENDPOINT));
		}
		if ( mdtInstanceConfig.getInstanceEndpoint() == null ) {
			if ( m_port != null || mdtInstanceConfig.getPort() != null ) {
				String host = FOption.getOrElse(System.getenv("LOCAL_HOST"), "localhost");
				int port = FOption.getOrElse(m_port, mdtInstanceConfig.getPort());
				mdtInstanceConfig.setPort(port);
				
				String endpoint = String.format("https://%s:%d/api/v3.0", host, port);
				mdtInstanceConfig.setInstanceEndpoint(endpoint);
			}
		}
		Preconditions.checkArgument(mdtInstanceConfig.getInstanceEndpoint() != null,
									"MDTInstance instanceEndpoint not specified");
		
		if ( m_managerEndpoint != null ) {
			mdtInstanceConfig.setManagerEndpoint(m_managerEndpoint);
		}
		else if ( System.getenv(ENV_MDT_MANAGER_ENDPOINT) != null ) {
			mdtInstanceConfig.setManagerEndpoint(System.getenv(ENV_MDT_MANAGER_ENDPOINT));
		}
		Preconditions.checkArgument(mdtInstanceConfig.getManagerEndpoint() != null,
									"MDTInstance managerEndpoint not specified");
		
		if ( mdtInstanceConfig.getPort() == null ) {
			Map<String,String> parts = extractHostAndPort(mdtInstanceConfig.getInstanceEndpoint());
			Preconditions.checkArgument(parts.containsKey("port"), "MDTInstance port not specified");
			mdtInstanceConfig.setPort(Integer.parseInt(parts.get("port")));
		}
		
		m_instanceVariables = buildInstanceVariables(mdtInstanceConfig);
		
		// 설정 파일을 variable substitution을 다시 수행한다.
		confJson = createStringSubstitutor(m_instanceVariables).replace(confJson);
		MDTInstanceConfig reloaded = MDTModelSerDe.MAPPER
												.readerFor(MDTInstanceConfig.class)
												.readValue(confJson);
		reloaded.setId(mdtInstanceConfig.getId());
		reloaded.setInstanceEndpoint(mdtInstanceConfig.getInstanceEndpoint());
		reloaded.setManagerEndpoint(mdtInstanceConfig.getManagerEndpoint());
		reloaded.setPort(mdtInstanceConfig.getPort());
		mdtInstanceConfig = reloaded;
		m_mdtInstanceConfig = mdtInstanceConfig;
		
		//
		// 다음과 같은 순서로 전역 설정 파일을 확인하여 사용한다.
		// 1) 명령어 인자를 통해 지정된 전역 설정 파일 위치
		// 2) 설정 파일에 지정된 전역 설정 파일 위치
		// 3) 홈 디렉토리에 'mdt_global_config.json' 파일
		// 4) 환경 변수에 설정된 전역 설정 파일 위치
		// 5) 전역 설정 파일을 사용하지 않음.
		//
		if ( m_globalConfigFile != null ) {
			// 명령어 인자를 통해 전역 설정 파일이 지정된 경우
			getLogger().warn("Using GlobalConfigFile (from command-line argument : {}",
							m_globalConfigFile.getAbsolutePath());
			if ( !m_globalConfigFile.exists() ) {
				String msg = String.format("GlobalConfig file does not exist: %s", m_globalConfigFile);
				throw new InitializationException(msg);
			}
			mdtInstanceConfig.setGlobalConfigFile(m_globalConfigFile);
		}
		else if ( mdtInstanceConfig.getGlobalConfigFile() != null ) {
			getLogger().warn("Using GlobalConfigFile (from instance configuration file: {}",
								mdtInstanceConfig.getGlobalConfigFile().getAbsolutePath());
			if ( !mdtInstanceConfig.getGlobalConfigFile().exists() ) {
				String msg = String.format("GlobalConfig file does not exist: %s", mdtInstanceConfig.getGlobalConfigFile());
				throw new InitializationException(msg);
			}
		}
		else {
			File globalConfigFile = homeDir.resolve(DEFAULT_GLOBAL_CONFIG_FILE).toFile();
			if ( globalConfigFile.exists() ) {
				getLogger().info("Use GlobalConfigFile (from instance home-directory) : {}",
									globalConfigFile.getAbsolutePath());
				mdtInstanceConfig.setGlobalConfigFile(globalConfigFile);
			}
			else {
				String globalConfigFilePath = System.getenv(ENV_MDT_GLOBAL_CONFIG_FILE);
				if ( globalConfigFilePath != null && new File(globalConfigFilePath).exists() ) {
					getLogger().info("Using GlobalConfigFile (from environment-variable) : {}",
										globalConfigFile.getAbsolutePath());
					mdtInstanceConfig.setGlobalConfigFile(new File(globalConfigFilePath));
				}
				else {
					getLogger().warn("GlobalConfigFile is not specified");
				}
			}
		}
		
		if ( m_keyStoreFile != null ) {
			if ( !m_keyStoreFile.exists() ) {
				String msg = String.format("Keystore file does not exist: %s", m_keyStoreFile);
				throw new InitializationException(msg);
			}
			mdtInstanceConfig.setKeyStoreFile(m_keyStoreFile);
		}
		else {
			String keyStoreFilePath = System.getenv(ENV_MDT_KEY_STORE_FILE);
			if ( keyStoreFilePath != null && new File(keyStoreFilePath).exists() ) {
				mdtInstanceConfig.setKeyStoreFile(new File(keyStoreFilePath));
			}
			else if ( mdtInstanceConfig.getKeyStoreFile() == null ) {
				File keyStoreFile = homeDir.resolve(DEFAULT_CERT_FILE).toFile();
				getLogger().warn("Keystore file not specified, using default: {}", keyStoreFile.getAbsolutePath());
				mdtInstanceConfig.setKeyStoreFile(keyStoreFile);
			}
		}

		if ( m_keyStorePassword != null ) {
			mdtInstanceConfig.setKeyStorePassword(m_keyStorePassword);
		}
		else {
			String password = System.getenv(ENV_MDT_KEY_STORE_PASSWORD);
			if ( password != null ) {
				mdtInstanceConfig.setKeyStorePassword(password);
			}
		}
		
		configureLogging();
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("MDTInstance Configuration:");
			getLogger().info("\tMDTInstance id: {}",  mdtInstanceConfig.getId());
			getLogger().info("\tInitial model: " + m_initModel);
			getLogger().info("\tConfiguration file: " + m_confFile);
			getLogger().info("\tMDTInstance endpoint: {}", mdtInstanceConfig.getInstanceEndpoint());
			getLogger().info("\tMDTManager endpoint: {}", mdtInstanceConfig.getManagerEndpoint());
			getLogger().info("\tMDTGlobalConfig file: {}", mdtInstanceConfig.getGlobalConfigFile());
			getLogger().info("\tKeyStore: {}", mdtInstanceConfig.getKeyStoreFile());
		}
		
		MDTGlobalConfigurations.setGlobalConfigFile(mdtInstanceConfig.getGlobalConfigFile());
		
		// Initial model을 읽어서 MDTModelLookup를 생성한다.
    	loadMDTModelLookup(m_initModel.toFile());
		
		ImplementationManager.init();
		ServiceConfig config = null;
		try {
			config = toServiceConfig(mdtInstanceConfig);
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

//		config = ServiceConfigAugmentor.augment(config, mdtInstanceConfig);
		runService(config, mdtInstanceConfig);
		
//		Map<String,String> parts = extractHostAndPort(mdtInstanceConfig.getInstanceEndpoint());
		if ( m_type == InstanceType.JAR ) {
			System.out.println("[***MARKER***] MDTInstance started: " + mdtInstanceConfig.getInstanceEndpoint());
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
				throw new IllegalArgumentException("Password for keystore or key is not specified in the configuration");
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
		
		// 추가의 service endpoint들의 설정 정보가 있으면 이를 추가한다.
		// FA3ST의 endpoint 설정에 추가한다.
		if ( instConf.getServiceEndpoints() != null ) {
			// MQTT endpoint 설정 정보가 있으면 추가한다.
			FOption.accept(instConf.getServiceEndpoints().getMqtt(), mqttConf -> {
				configs.add(new MqttEndpointConfig(mqttConf));
				getLogger().info("Register MQTT endpoint: {}", mqttConf);
			});
			
			// ROS2 endpoint 설정 정보가 있으면 추가한다.
			FOption.accept(instConf.getServiceEndpoints().getRos2(), rosConf -> {
				Ros2EndpointConfig c = new Ros2EndpointConfig();
				c.setConnectionConfig(rosConf.getConnectionConfig());
				c.setMessages(rosConf.getMessages());
				configs.add(c);
				
				getLogger().info("Register ROS2 endpoint: {}", c);
			});
			
			FOption.accept(instConf.getServiceEndpoints().getCompanion(), companionConfig -> {
				companionConfig.setEnvironments(m_instanceVariables);
				
				ProgramCompanionConfig c = new ProgramCompanionConfig();
				c.setProgramConfig(companionConfig);
				configs.add(c);
				
				getLogger().info("Register Companion endpoint: {}", c);
			});
		}
		
		return configs;
	}
	
	private PersistenceConfig<?> loadPersistenceConfig(MDTInstanceConfig instConf) {
		PersistenceConfig<?> topConfig = PersistenceInMemoryConfig.builder()
																.initialModelFile(m_initModel.toFile())
																.build();
		
		if ( !instConf.getAssetVariables().isEmpty() ) {
			AssertVariableBasedPersistenceConfig.Core assetConfig = new AssertVariableBasedPersistenceConfig.Core();
			assetConfig.setAssetVariableConfigs(instConf.getAssetVariables());
			topConfig = new AssertVariableBasedPersistenceConfig(assetConfig, topConfig);
		}
		if ( !instConf.getTimeSeriesSubmodels().isEmpty() ) {
			TimeSeriesPersistenceStackConfig.Core tsConfig = new TimeSeriesPersistenceStackConfig.Core();
			tsConfig.setTimeSeriesSubmodels(instConf.getTimeSeriesSubmodels());
			topConfig = new TimeSeriesPersistenceStackConfig(tsConfig, topConfig);
		}
		
		for ( int i = instConf.getPersistenceStacks().size() - 1; i >= 0; --i ) {
			MDTPersistenceStackConfig stackConfig = instConf.getPersistenceStacks().get(i);
			if ( stackConfig instanceof ConcurrentPersistenceConfig.Core ccConfig ) {
				topConfig = new ConcurrentPersistenceConfig(ccConfig, topConfig);
			}
			else {
				throw new IllegalArgumentException("Unknown persistence stack config: " + stackConfig);
			}
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
    private void runService(ServiceConfig config, MDTInstanceConfig instConf) {
		try {
			serviceRef.set(new MDTService(new MDTServiceConfig(config, instConf)));
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
	
	private Map<String,String> buildInstanceVariables(MDTInstanceConfig config) {
		return Map.of(
			"MDT_INSTANCE_ID", config.getId(),
			"MDT_INSTANCE_ENDPOINT", config.getInstanceEndpoint(),
			"MDT_MANAGER_ENDPOINT", config.getManagerEndpoint(),
			"MDT_INSTANCE_PORT", config.getPort() != null ? Integer.toString(config.getPort()) : "",
			"MDT_INSTANCE_DIR", FileUtils.getCurrentWorkingDirectory().getAbsolutePath()
		);
	}
	
	private StringSubstitutor createStringSubstitutor(Map<String,String> variables) {
		StringLookup customLookup = StringLookupFactory.INSTANCE.mapStringLookup(variables);
		StringLookup compositeLookup = StringLookupFactory.INSTANCE.interpolatorStringLookup(
			Map.of(), 		// 프리픽스 기반 lookup 없음
			customLookup,	// ${foo} → "FOO_VALUE"
			true			// sys/env/java/... 기본 lookup 포함
		);
		return new StringSubstitutor(compositeLookup);
	}
}
