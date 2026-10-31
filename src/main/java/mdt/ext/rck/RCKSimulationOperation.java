package mdt.ext.rck;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.concurrent.GuardedBy;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;

import utils.UnitUtils;
import utils.async.Executions;
import utils.func.FOption;
import utils.func.Result;

import mdt.assetconnection.operation.AbstractJavaOperationProvider;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.client.HttpMDTManager;
import mdt.config.MDTInstanceConfig;
import mdt.config.MDTService;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.value.PropertyValue;


/**
 * RCK 프레스 공정 시뮬레이션을 수행하는 MDT 오퍼레이션.
 * <p>
 * 호출될 때마다 {@link RCKSimulation}을 하나 만들어 끝까지 구동하고, 마지막 진행 상황과 최종 상태, 결과 영상 URL을
 * 출력 변수에 기록한다. 구동하는 동안에는 {@link MDTSimulationOutputs}로 이 인스턴스의 출력 인자에 진행 상황을
 * 반영한다.
 * <p>
 * 비동기 호출({@link #invokeAsync})은 시뮬레이션을 시작만 하고 바로 반환한다. 끝을 기다리며 스레드를 붙잡지 않고,
 * 시뮬레이션이 끝나면 그 결과로 성공/실패 콜백을 부른다. 동기 호출({@link #invokeSync})은 끝날 때까지 기다리며,
 * 기다리다 인터럽트되면 시뮬레이션을 취소한다.
 * <p>
 * 상태를 담는 출력은 두 가지이며 용도가 다르다.
 * <ul>
 *   <li>{@code FinalState} - 프로그램이 읽는 결과 코드. 최종 상태 경로에서 앞의 {@code /}를 뗀 값
 *       ({@code Completed}, {@code Stopped})이며, 오퍼레이션이 성공할 때만 기록된다. 실패·취소·시간 초과는
 *       예외로 알린다.</li>
 *   <li>{@code State} - 사람이 보는 실시간 표시 이름(예: {@code 시뮬레이션 진행중}). 진행 중 상태가 바뀔 때마다
 *       {@link MDTSimulationOutputs}가 갱신하며, 실패·취소로 끝난 경우에도 마지막 상태가 남는다. 표시 문구는
 *       바뀔 수 있으므로 분기 조건으로 쓰지 않는다.</li>
 * </ul>
 * <p>
 * 설정 인자:
 * <ul>
 *   <li>{@code clientId} / {@code processName} / {@code rckServerUrl} (필수) - 시뮬레이터 등록 ID, 공정명,
 *       WebSocket 서버 URL.</li>
 *   <li>{@code responseTimeout} (선택, 기본 2분) - 시뮬레이터 응답 대기 시간({@link RCKSimulation#setResponseTimeout}).</li>
 *   <li>{@code pingInterval} / {@code pongTimeout} (선택, 기본 10초 / 2초) - 연결 유지 ping 간격과 pong 대기 시간.</li>
 *   <li>{@code maxDuration} (선택, 기본 1시간) - 시뮬레이션 전체의 최대 수행 시간. 넘으면 시뮬레이션을 취소하고
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
	private static final Duration DEFAULT_MAX_DURATION = Duration.ofHours(1);

	private final ServiceContext m_serviceContext;
	private final String m_clientId;
	private final String m_rckServerUrl;
	private final String m_processName;
	private final Duration m_responseTimeout;
	private final Duration m_pingInterval;
	private final Duration m_pongTimeout;
	private final Duration m_maxDuration;

	// MDT 인스턴스 매니저 클라이언트. 처음 필요할 때 만들어 이후 호출에서 재사용한다(접속에 실패하면 다음에 다시 시도).
	@GuardedBy("this") private MDTInstanceManager m_mdtManager;

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
		m_maxDuration = getDurationArgument("maxDuration", DEFAULT_MAX_DURATION);

		if ( s_logger.isInfoEnabled() ) {
			Object path = (opRef != null) ? IdShortPath.fromReference(opRef) : null;
			s_logger.info("Operation (path={}) is ready: clientId={}, processName={}, rckServerUrl={}",
							path, m_clientId, m_processName, m_rckServerUrl);
		}
	}

	/**
	 * 시뮬레이션을 시작하고 바로 반환한다. 시뮬레이션이 끝나면 출력 변수를 기록하고 {@code callbackSuccess}를,
	 * 실패·취소·시간 초과로 끝나면 {@code callbackFailure}를 부른다. 입력 변수나 출력 변수가 잘못되었으면 시작하지 않고
	 * 예외를 던진다.
	 */
	@Override
	public void invokeAsync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars,
							BiConsumer<OperationVariable[], OperationVariable[]> callbackSuccess,
							Consumer<Throwable> callbackFailure) throws Exception {
		Outputs outputs = resolveOutputs(toVarMap(outputVars));
		startSimulation(toVarMap(inputVars), outcome -> {
			try {
				Exception failure = outcome.failure();
				if ( failure != null ) {
					// 기본 invokeAsync와 같이 실패 원인을 ExecutionException으로 감싸 전달한다.
					s_logger.error("Failed to invoke AASOperation: {}", ""+failure);
					callbackFailure.accept((failure instanceof ExecutionException) ? failure
																					: new ExecutionException(failure));
					return;
				}
				outputs.write(outcome.simulation());
				callbackSuccess.accept(outputVars, inoutputVars);
			}
			catch ( Throwable e ) {
				callbackFailure.accept(e);
			}
		});
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
								Map<String, SubmodelElement> inoutputVarList,
								Map<String, SubmodelElement> outputVarList) throws Exception {
		Outputs outputs = resolveOutputs(outputVarList);

		CompletableFuture<Outcome> done = new CompletableFuture<>();
		RCKSimulation sim = startSimulation(inputVarList, done::complete);
		Outcome outcome;
		try {
			outcome = done.get();
		}
		catch ( InterruptedException e ) {
			// 끝을 기다리지 않고 빠져나가면 시뮬레이터와의 통신이 계속되므로 취소한다(중지 명령도 보낸다).
			s_logger.warn("interrupted while waiting; cancelling the simulation");
			sim.cancel(true);
			throw e;
		}

		Exception failure = outcome.failure();
		if ( failure != null ) {
			throw failure;
		}
		outputs.write(sim);
	}

	/**
	 * 시뮬레이션을 만들어 시작한다. 시뮬레이션이 어떤 경로로 끝나든 {@code onFinished}가 차트 lock 밖에서 한 번 불린다.
	 * {@code maxDuration}이 지나면 시뮬레이션을 취소한다.
	 *
	 * @param inputVarList	입력 변수
	 * @param onFinished	시뮬레이션이 끝났을 때 결과를 받을 콜백
	 * @return	시작한 시뮬레이션
	 * @throws IllegalArgumentException	입력 변수 {@code LayoutName}이 없거나 비어 있는 경우
	 */
	private RCKSimulation startSimulation(Map<String, SubmodelElement> inputVarList, Consumer<Outcome> onFinished) {
		String layoutName = getRequiredStringVariableValue(inputVarList, "LayoutName");
		if ( layoutName == null || layoutName.isBlank() ) {
			throw new IllegalArgumentException("input variable 'LayoutName' is empty");
		}

		var ctx = new RCKSimulationContext(m_rckServerUrl, m_clientId, m_processName, layoutName);
		RCKSimulation sim = new RCKSimulation(ctx, createObserver());
		sim.setResponseTimeout(m_responseTimeout);
		sim.setPingInterval(m_pingInterval);
		sim.setPongTimeout(m_pongTimeout);

		AtomicBoolean timedOut = new AtomicBoolean();
		ScheduledFuture<?> timer = Executions.getExecutor().schedule(() -> {
			s_logger.warn("simulation did not finish within {}; cancelling it", m_maxDuration);
			timedOut.set(true);
			sim.cancel(true);
		}, m_maxDuration.toMillis(), TimeUnit.MILLISECONDS);
		sim.whenFinishedAsync(result -> {
			timer.cancel(false);
			onFinished.accept(new Outcome(sim, result, timedOut.get(), m_maxDuration));
		});

		try {
			sim.start();
		}
		catch ( RuntimeException e ) {
			// 시작 중 실패(초기 상태 진입 실패)는 종료 리스너가 실패로 알린다. 시작조차 못 했으면 여기서 던진다.
			if ( !sim.isDone() ) {
				timer.cancel(false);
				throw e;
			}
		}
		return sim;
	}

	/**
	 * 끝난 시뮬레이션과 그 결과.
	 *
	 * @param simulation	끝난 시뮬레이션
	 * @param result		차트 실행 결과
	 * @param timedOut		{@code maxDuration}이 지나 취소되었는지 여부
	 * @param maxDuration	최대 수행 시간 (오류 메시지용)
	 */
	private static record Outcome(RCKSimulation simulation, Result<RCKSimulationContext> result, boolean timedOut,
								Duration maxDuration) {
		/**
		 * 실패를 예외로 돌려준다. 성공이면 {@code null}.
		 * 실패는 원인을 담은 {@link ExecutionException}, 시간 초과는 {@link TimeoutException}, 그 밖의 취소는
		 * {@link CancellationException}이다.
		 */
		Exception failure() {
			if ( result.isSuccessful() ) {
				return null;
			}
			if ( result.isFailed() ) {
				return new ExecutionException(result.getCause());
			}
			return timedOut ? new TimeoutException("simulation did not finish within " + maxDuration)
							: new CancellationException("simulation was cancelled");
		}
	}

	/**
	 * 오퍼레이션의 출력 변수들. 출력 변수가 없으면 해당 항목은 {@code null}이며 기록하지 않는다.
	 */
	private static record Outputs(Property progress, Property production, Property finalState,
								Property averageUtilization, Property videoUrl) {
		/**
		 * 끝난 시뮬레이션의 결과를 출력 변수에 기록한다.
		 */
		void write(RCKSimulation sim) throws Exception {
			RCKSimulationContext ctx = sim.getContext();
			RCKSimulationStatus status = ctx.getLatestStatus();
			ElementValues.update(progress, PropertyValue.INTEGER(status.progress()));
			ElementValues.update(production, PropertyValue.INTEGER(status.production()));
			ElementValues.update(finalState,
								PropertyValue.STRING(RCKSimulation.toResultCode(sim.getCurrentState().getPath())));
			ElementValues.update(averageUtilization,
								MDTSimulationOutputs.toUtilizationJson(status.averageUtilizations()));

			// 영상 정보를 받기 전에 끝났으면(중지 등) URL은 빈 문자열로 둔다.
			VideoInfo video = ctx.getVideoInfo();
			ElementValues.update(videoUrl, (video != null) ? video.downloadUrl() : "");
		}
	}

	/**
	 * 출력 변수를 찾는다. 시뮬레이션을 시작하기 전에 형식을 확인하기 위해 먼저 부른다.
	 *
	 * @throws IllegalArgumentException	출력 변수의 형식이 맞지 않는 경우
	 */
	private Outputs resolveOutputs(Map<String, SubmodelElement> outputVarList) {
		return new Outputs(getPropertyVariable(outputVarList, "Progress", DataTypeDefXsd.INT),
							getPropertyVariable(outputVarList, "Production", DataTypeDefXsd.INT),
							getPropertyVariable(outputVarList, "FinalState", DataTypeDefXsd.STRING),
							getPropertyVariable(outputVarList, "AverageUtilization", DataTypeDefXsd.STRING),
							getPropertyVariable(outputVarList, "SimulationVideoUrl", DataTypeDefXsd.STRING));
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
			MDTSimulationOutputs outputs = new MDTSimulationOutputs(getMdtManager(), instanceId);
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
	 * MDT 인스턴스 매니저 클라이언트를 돌려준다. 처음 호출할 때 기본 설정으로 접속한다. 접속에 실패하면 캐시하지 않으므로
	 * 다음 호출에서 다시 시도한다.
	 *
	 * @throws RuntimeException	MDT 인스턴스 매니저에 접속하지 못한 경우
	 */
	private synchronized MDTInstanceManager getMdtManager() {
		if ( m_mdtManager == null ) {
			m_mdtManager = HttpMDTManager.connectWithDefault().getInstanceManager();
		}
		return m_mdtManager;
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
