package mdt.endpoint.rt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.Maps;

import okhttp3.HttpUrl;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.model.ServiceSpecificationProfile;

import utils.KeyValue;
import utils.async.PeriodicLoopService;
import utils.http.HttpRESTfulClient;
import utils.stream.FStream;

import mdt.FaaastRuntime;
import mdt.model.MDTModelSerDe;

/**
 * 로봇 트윈(RESTful gateway)의 상태를 주기적으로 조회하는 FA³ST endpoint.
 * <p>
 * {@link #start()}가 호출되면 {@link PeriodicLoopService}를 기동시켜 설정된 주기마다
 * 로봇 트윈의 전체 상태 변수를 조회하고, 그 결과를 {@link RobotTwinStateSnapshot}으로 보관한다.
 * 조회는 두 단계로 이루어진다.
 * <ol>
 *   <li>{@code GET .../variables}로 변수 카탈로그를 읽어 전체 변수 이름을 얻는다.
 *       변수 목록은 로봇 트윈 설정으로 고정되므로 한번 읽은 뒤 캐슁한다.</li>
 *   <li>{@code GET .../variables?names=<전체 변수>}로 모든 변수의 최신 값을 한번에 읽는다.</li>
 * </ol>
 * 조회 중 오류가 발생한 경우에는 로그만 남기고 다음 주기에 다시 시도한다. 로봇 트윈이 재시작되어
 * 변수 목록이 바뀌었을 수 있으므로, 실패한 경우에는 캐슁된 변수 목록을 버리고 다음 주기에
 * 카탈로그부터 다시 읽는다.
 * <p>
 * {@link #stop()}이 호출되면 주기적 조회 작업을 종료시킨다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RobotTwinEndpoint implements Endpoint<RobotTwinEndpointConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(RobotTwinEndpoint.class);

	private static final String EP_GET_VARIABLES_FMT = "%s/api/v1/robot_twins/%s/variables";
	private static final String POLLER_NAME = "robot-twin-poller";
	private static final long STOP_TIMEOUT_SECS = 10;

	private FaaastRuntime m_faaast;
	private RobotTwinEndpointConfig m_config;
	private String m_robotTwinBaseUrl;
	private RobotTwinConfig m_rtConnConfig;
	private HttpRESTfulClient m_restClient;
	private String m_variablesUrl;

	private volatile PeriodicLoopService m_poller;
	// 로봇 트윈의 변수 카탈로그. 최초 조회시 채워지고, 조회 실패시 null로 초기화된다.
	private volatile @Nullable List<String> m_variableNames = null;
	private volatile @Nullable RobotTwinStateSnapshot m_lastSnapshot = null;

	private static final JsonMapper JSON_MAPPER = MDTModelSerDe.getJsonMapper();

	@Override
	public void init(CoreConfig coreConfig, RobotTwinEndpointConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		m_config = config;

		m_faaast = FaaastRuntime.getOrCreate(serviceContext);
		
		m_robotTwinBaseUrl = m_config.getRobotTwinBaseUrl();
		if ( m_robotTwinBaseUrl.endsWith("/") ) {
			m_robotTwinBaseUrl = m_robotTwinBaseUrl.substring(0, m_robotTwinBaseUrl.length()-1);
		}
		m_variablesUrl = String.format(EP_GET_VARIABLES_FMT, m_robotTwinBaseUrl, m_config.getTwinId());

		try {
			m_restClient = HttpRESTfulClient.newDefaultClient();
		}
		catch ( Exception e ) {
			throw new ConfigurationInitializationException("Failed to read global configuration, cause=" + e);
		}
	}

	@Override
	public RobotTwinEndpointConfig asConfig() {
		return m_config;
	}

	@Override
	public List<ServiceSpecificationProfile> getProfiles() {
		return m_config.getProfiles();
	}

    @Override
    public void start() throws EndpointException {
		s_logger.info("Starting service: {}", this);

//		// 로봇 트윈의 전체 상태를 주기적으로 조회하는 서비스를 시작시킨다.
//		// 로봇 트윈이 일시적으로 내려갈 수 있기 때문에 조회 실패가 서비스를 중단시키지 않도록 한다.
//		m_poller = PeriodicLoopService.builder(this::pollRobotTwinState)
//										.name(POLLER_NAME)
//										.interval(m_config.getPollInterval())
//										.stopOnError(false)
//										.build();
//		try {
//			m_poller.startAsync().awaitRunning();
//		}
//		catch ( Exception e ) {
//			m_poller = null;
//			throw new EndpointException("Failed to start robot-twin state poller: " + this, e);
//		}
    }

    @Override
    public void stop() {
//    	PeriodicLoopService poller = m_poller;
//    	if ( poller == null ) {
//    		return;
//    	}
//    	m_poller = null;
//
//		s_logger.info("Stopping service: {}", this);
//
//    	// 진행 중인 조회 작업이 완료된 후 loop이 종료되기 때문에 종료를 기다린다.
//    	poller.stopAsync();
//    	try {
//    		poller.awaitTerminated(STOP_TIMEOUT_SECS, TimeUnit.SECONDS);
//    	}
//    	catch ( TimeoutException e ) {
//    		s_logger.warn("Timed out while stopping robot-twin state poller: timeout={}s", STOP_TIMEOUT_SECS);
//    	}
//    	catch ( IllegalStateException e ) {
//    		s_logger.warn("Failed to stop robot-twin state poller, cause=" + poller.failureCause(), e);
//    	}
    }

    /**
     * 가장 최근에 조회된 로봇 트윈의 전체 상태를 반환한다.
     *
     * @return 최근 상태 스냅샷. 아직 한번도 조회에 성공하지 못한 경우는 {@code null}.
     */
    public @Nullable RobotTwinStateSnapshot getLastStateSnapshot() {
    	return m_lastSnapshot;
    }

	@Override
	public String toString() {
		return String.format("RobotTwinEndpoint[conn=%s, twin=%s, pollInterval=%s]",
							m_rtConnConfig, m_config.getTwinId(), m_config.getPollInterval());
	}

	/**
	 * 로봇 트윈의 전체 상태 변수를 조회하여 최근 스냅샷을 갱신한다.
	 * <p>
	 * {@link PeriodicLoopService}에 의해 주기적으로 호출된다.
	 */
	private void pollRobotTwinState() {
		try {
			List<String> varNames = m_variableNames;
			if ( varNames == null ) {
				varNames = loadVariableNames();
				m_variableNames = varNames;
			}
			if ( varNames.isEmpty() ) {
				return;
			}

			Map<String,RobotTwinVariable> variables = readVariables(varNames);
			m_lastSnapshot = new RobotTwinStateSnapshot(Instant.now(), variables);

			if ( s_logger.isDebugEnabled() ) {
				s_logger.debug("Polled robot-twin state: {}", m_lastSnapshot);
			}
		}
		catch ( Exception e ) {
			// 로봇 트윈이 재시작되어 변수 목록이 바뀌었을 수 있기 때문에
			// 캐슁된 변수 목록을 버리고 다음 주기에 카탈로그부터 다시 읽도록 한다.
			m_variableNames = null;
			s_logger.warn("Failed to poll robot-twin state: url={}, cause={}", m_variablesUrl, e.toString());
		}
	}

	/**
	 * 로봇 트윈에 정의된 전체 상태 변수의 이름을 조회한다.
	 *
	 * @return 변수 이름 리스트.
	 */
	private List<String> loadVariableNames() {
		JsonNode respNode = m_restClient.get(m_variablesUrl, HttpRESTfulClient.JSON_DESER);
		JsonNode varsNode = respNode.get("variables");
		if ( varsNode == null ) {
			throw new IllegalStateException("Invalid robot-twin variable catalog: " + respNode);
		}

		List<String> varNames = FStream.from(varsNode.elements())
										.map(node -> node.get("name").asText())
										.toList();
		s_logger.info("Loaded robot-twin variable catalog: twin={}, variables={}",
						m_config.getTwinId(), varNames);

		return varNames;
	}

	/**
	 * 주어진 이름의 상태 변수들의 최신 값을 한번의 요청으로 조회한다.
	 *
	 * @param varNames 조회할 변수 이름들.
	 * @return 변수 이름을 키로 하는 조회 결과.
	 */
	private Map<String,RobotTwinVariable> readVariables(List<String> varNames) {
		HttpUrl url = HttpUrl.get(m_variablesUrl)
							.newBuilder()
							.addQueryParameter("names", FStream.from(varNames).join(","))
							.build();

		JsonNode respNode = m_restClient.get(url, HttpRESTfulClient.JSON_DESER);
		JsonNode valuesNode = respNode.get("values");
		if ( valuesNode == null ) {
			throw new IllegalStateException("Invalid robot-twin variable values: " + respNode);
		}

		return FStream.from(valuesNode.properties())
						.mapToKeyValue(ent -> KeyValue.of(ent.getKey(),
														parseVariable(ent.getKey(), ent.getValue())))
						.toMap(Maps.newLinkedHashMap());
	}

	private RobotTwinVariable parseVariable(String name, JsonNode varNode) {
		try {
			RobotTwinVariable variable = JSON_MAPPER.treeToValue(varNode, RobotTwinVariable.class);
			if ( variable.getName() == null ) {
				variable.setName(name);
			}

			return variable;
		}
		catch ( Exception e ) {
			throw new IllegalStateException("Failed to parse robot-twin variable: name=" + name
											+ ", value=" + varNode, e);
		}
	}
}
