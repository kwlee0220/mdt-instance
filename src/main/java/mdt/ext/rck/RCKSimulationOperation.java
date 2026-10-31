package mdt.ext.rck;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;

import utils.UnitUtils;
import utils.func.FOption;

import mdt.assetconnection.operation.AbstractJavaOperationProvider;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.config.MDTInstanceConfig;
import mdt.config.MDTService;
import mdt.ext.rck.VideoMessages.VideoInfo;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.value.PropertyValue;


/**
 * RCK 프레스 공정 시뮬레이션을 수행하는 MDT 오퍼레이션.
 * <p>
 * 호출될 때마다 {@link RCKSimulation}을 하나 만들어 끝까지 구동하고, 마지막 진행 상황과 최종 상태, 결과 영상 URL을
 * 출력 변수에 기록한다. 구동하는 동안에는 {@link MDTSimulationOutputs}로 이 인스턴스의 출력 인자에 진행 상황을
 * 반영한다.
 * <p>
 * 설정 인자:
 * <ul>
 *   <li>{@code clientId} / {@code processName} / {@code rckServerUrl} (필수) - 시뮬레이터 등록 ID, 공정명,
 *       WebSocket 서버 URL.</li>
 *   <li>{@code responseTimeout} (선택, 기본 2분) - 시뮬레이터 응답 대기 시간({@link RCKSimulation#setResponseTimeout}).</li>
 *   <li>{@code pingInterval} / {@code pongTimeout} (선택, 기본 10초 / 2초) - 연결 유지 ping 간격과 pong 대기 시간.</li>
 *   <li>{@code maxDuration} (선택, 기본 무제한) - 시뮬레이션 전체의 최대 수행 시간. 넘으면 시뮬레이션을 취소하고
 *       {@link TimeoutException}으로 실패한다.</li>
 * </ul>
 * 시간 값은 {@link UnitUtils#parseDuration(String)} 형식(예: {@code "90s"}, {@code "2m"})이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationOperation extends AbstractJavaOperationProvider {
	private final static Logger s_logger = LoggerFactory.getLogger(RCKSimulationOperation.class);

	private static final Duration DEFAULT_PING_INTERVAL = Duration.ofSeconds(10);
	private static final Duration DEFAULT_PONG_TIMEOUT = Duration.ofSeconds(2);

	private final ServiceContext m_serviceContext;
	private final String m_clientId;
	private final String m_rckServerUrl;
	private final String m_processName;
	private final Duration m_responseTimeout;
	private final Duration m_pingInterval;
	private final Duration m_pongTimeout;
	private final Duration m_maxDuration;	// null이면 무제한

	public RCKSimulationOperation(ServiceContext serviceContext, Reference opRef,
									JavaOperationProviderConfig config)
		throws ConfigurationInitializationException {
		super(serviceContext, opRef, config);

		m_serviceContext = serviceContext;
		m_clientId = getRequiredNonEmptyArgument("clientId");
		m_processName = getRequiredNonEmptyArgument("processName");
		m_rckServerUrl = getRequiredNonEmptyArgument("rckServerUrl");
		m_responseTimeout = getDurationArgument("responseTimeout", RCKSimulation.DEFAULT_RESPONSE_TIMEOUT);
		m_pingInterval = getDurationArgument("pingInterval", DEFAULT_PING_INTERVAL);
		m_pongTimeout = getDurationArgument("pongTimeout", DEFAULT_PONG_TIMEOUT);
		m_maxDuration = getDurationArgument("maxDuration", null);

		if ( s_logger.isInfoEnabled() ) {
			Object path = (opRef != null) ? IdShortPath.fromReference(opRef) : null;
			s_logger.info("Operation (path={}) is ready: clientId={}, processName={}, rckServerUrl={}",
							path, m_clientId, m_processName, m_rckServerUrl);
		}
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
								Map<String, SubmodelElement> inoutputVarList,
								Map<String, SubmodelElement> outputVarList) throws Exception {
		String layoutName = getRequiredStringVariableValue(inputVarList, "LayoutName");
		if ( layoutName == null || layoutName.isBlank() ) {
			throw new IllegalArgumentException("input variable 'LayoutName' is empty");
		}

		Property progressProp = getPropertyVariable(outputVarList, "Progress", DataTypeDefXsd.INT);
		Property productionProp = getPropertyVariable(outputVarList, "Production", DataTypeDefXsd.INT);
		Property stateProp = getPropertyVariable(outputVarList, "FinalState", DataTypeDefXsd.STRING);
		Property avgUtil = getPropertyVariable(outputVarList, "AverageUtilization", DataTypeDefXsd.STRING);
		Property videoUrlProp = getPropertyVariable(outputVarList, "SimulationVideoUrl", DataTypeDefXsd.STRING);

		var ctx = new RCKSimulationContext(m_rckServerUrl, m_clientId, m_processName, layoutName);
		RCKSimulation sim = new RCKSimulation(ctx, createObserver());
		sim.setResponseTimeout(m_responseTimeout);
		sim.setPingInterval(m_pingInterval);
		sim.setPongTimeout(m_pongTimeout);

		sim.start();
		try {
			// 시뮬레이션이 종료될 때까지 대기한다. (시뮬레이션이 실패한 경우 ExecutionException이 발생한다)
			if ( m_maxDuration != null ) {
				sim.get(m_maxDuration.toMillis(), TimeUnit.MILLISECONDS);
			}
			else {
				sim.get();
			}
		}
		catch ( InterruptedException | TimeoutException e ) {
			// 끝을 기다리지 않고 빠져나가면 시뮬레이터와의 통신이 계속되므로 취소한다(중지 명령도 보낸다).
			s_logger.warn("cancelling the simulation: cause={}", ""+e);
			sim.cancel(true);
			throw e;
		}

		RCKSimulationStatus status = ctx.getLatestStatus();
		ElementValues.update(progressProp, PropertyValue.INTEGER(status.progress()));
		ElementValues.update(productionProp, PropertyValue.INTEGER(status.production()));
		ElementValues.update(stateProp, PropertyValue.STRING(sim.getCurrentState().getPath()));
		ElementValues.update(avgUtil, MDTSimulationOutputs.toUtilizationJson(status.averageUtilizations()));

		// 영상 정보를 받기 전에 끝났으면(중지 등) URL은 빈 문자열로 둔다.
		VideoInfo video = ctx.getVideoInfo();
		ElementValues.update(videoUrlProp, (video != null) ? video.downloadUrl() : "");
	}

	/**
	 * 시뮬레이션 진행을 이 인스턴스의 출력 인자에 반영할 관찰자를 만든다.
	 * <p>
	 * 인스턴스 식별자를 알 수 없거나 MDT 인스턴스 매니저에 접속하지 못하면 경고 로그를 남기고, 진행 상황을 반영하지
	 * 않는 관찰자를 돌려준다(시뮬레이션은 그대로 수행한다).
	 *
	 * @return	진행 상황 관찰자
	 */
	SimulationObserver createObserver() {
		String instanceId = findInstanceId();
		if ( instanceId == null ) {
			s_logger.warn("unknown MDT instance id; simulation progress is not reported to MDT");
			return SimulationObserver.NONE;
		}
		try {
			MDTSimulationOutputs outputs = MDTSimulationOutputs.connect(instanceId);
			outputs.reset();
			s_logger.info("reporting simulation progress to MDT instance: {}", instanceId);

			return outputs;
		}
		catch ( Exception e ) {
			s_logger.warn("failed to connect MDT InstanceManager; simulation progress is not reported: {}", ""+e);
			return SimulationObserver.NONE;
		}
	}

	/**
	 * 이 인스턴스의 식별자를 찾는다. 인스턴스 설정의 id({@code --id}, {@code MDT_INSTANCE_ID}, 설정 파일 순으로
	 * 정해진 값)를 쓰고, 서비스 문맥이 없으면 {@code MDT_INSTANCE_ID} 환경변수를 쓴다.
	 */
	private String findInstanceId() {
		if ( m_serviceContext instanceof MDTService mdtCtxt ) {
			String id = FOption.ofNullable(mdtCtxt.getInstanceConfig())
								.map(MDTInstanceConfig::getId)
								.getOrNull();
			if ( id != null ) {
				return id;
			}
		}
		return System.getenv("MDT_INSTANCE_ID");
	}

	private String getRequiredNonEmptyArgument(String name) throws ConfigurationInitializationException {
		String value = assertStringArgument(name);
		if ( value.isBlank() ) {
			throw new ConfigurationInitializationException("argument '" + name + "' is empty: " + m_config);
		}
		return value;
	}

	private Duration getDurationArgument(String name, Duration defaultValue)
		throws ConfigurationInitializationException {
		String str = getStringArgument(name);
		if ( str == null ) {
			return defaultValue;
		}
		try {
			Duration dur = UnitUtils.parseDuration(str);
			if ( !dur.isPositive() ) {
				throw new IllegalArgumentException("not positive");
			}
			return dur;
		}
		catch ( RuntimeException e ) {
			throw new ConfigurationInitializationException("invalid argument '" + name + "': " + str
															+ " (" + e.getMessage() + ")");
		}
	}
}
