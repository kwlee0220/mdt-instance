package mdt.assetconnection.operation.robot;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.Preconditions;
import utils.async.AbstractThreadedExecution;

import mdt.ConnectionConfigRef;
import mdt.MDTGlobalConfigurations;
import mdt.assetconnection.operation.AbstractJavaOperationProvider;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.endpoint.rt.RobotTwinConfig;
import mdt.model.MDTModelSerDe;
import mdt.robot.client.OperationFailedException;
import mdt.robot.client.OperationResult;
import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.StepResult;
import mdt.rt.Ros2SerDes;


/**
 * AAS 연산 Provider의 로봇 트윈 연결 설정과 입력·출력 변환 보조 기능을 제공한다.
 * <p>
 * 신규 Provider는 대응하는 클라이언트 연산이 있으면 {@link #createRobotTwinClient()}와
 * {@link #runOperation(AbstractThreadedExecution)}을 우선 사용하고 AAS 변환·출력 처리를 담당한다.
 * 상태 변수 조회와 갱신 대기도 RobotTwinClient 및 클라이언트의 관측 연산을 사용한다.
 * 정착 판정은 클라이언트의 {@code WaitUntilStill}에 맡긴다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class RobotTwinOperationProvider extends AbstractJavaOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(RobotTwinOperationProvider.class);

	private static final String ARG_ROBOT_TWIN_CONFIG = "robotTwinConfig";
	private static final String ARG_ROBOT_TWIN_CONFIG_NAME = "robotTwinConfigName";	// 이전 형식
	private static final Duration OPERATION_POLL_INTERVAL = Duration.ofSeconds(1);
	// 로봇 트윈 설정의 연산별 기본 시간 상한(default_timeout_sec)과 같은 값.
	private static final Duration TWIN_DEFAULT_TIME_LIMIT = Duration.ofSeconds(60);
	// 로봇 트윈의 시간 초과 판정(워치독 주기 1초)이 클라이언트 타임아웃보다 먼저 도착하도록 두는 여유.
	private static final Duration CLIENT_TIMEOUT_MARGIN = Duration.ofSeconds(30);

	protected final RobotTwinConfig m_rtConfig;

	protected RobotTwinOperationProvider(ServiceContext serviceContext, Reference operationRef,
										JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);

		// 'robotTwinConfig' 인자는 전역 설정(robotTwins)의 항목 이름(문자열)이거나 접속 정보 객체이다.
		// 생략하면 전역 설정의 default 항목을 사용한다. 이전 형식의 'robotTwinConfigName'도 받는다.
		try {
			JsonNode argsNode = MDTModelSerDe.MAPPER.valueToTree(m_args);
			ConnectionConfigRef<RobotTwinConfig> ref
					= ConnectionConfigRef.load(argsNode, ARG_ROBOT_TWIN_CONFIG, ARG_ROBOT_TWIN_CONFIG_NAME,
												RobotTwinConfig.class);
			m_rtConfig = ref.resolve(MDTGlobalConfigurations::getRobotTwinConfig);
		}
		catch ( IOException e ) {
			throw new ConfigurationInitializationException(String.format("Invalid robot twin configuration: %s, cause=%s",
																		config, e.getMessage()), e);
		}
	}

	/**
	 * 클라이언트 연산을 사용하는 Provider의 연결 설정. Provider 생성 시 한 번 호출하여 재사용한다.
	 * 각 Provider가 자신의 RobotTwinClient 하나를 사용한다.
	 */
	protected final RobotTwinClient createRobotTwinClient() {
		return RobotTwinClient.builder().baseUrl(m_rtConfig.getRobotTwinBaseUrl())
				.twinId(m_rtConfig.getTwinId()).pollInterval(OPERATION_POLL_INTERVAL).build();
	}

	/**
	 * 단일·조합 연산을 동기로 실행하고 실행 래퍼 대신 실제 원인 예외를 전달한다.
	 * 인터럽트와 취소는 그대로 전파하며, 반환된 결과의 도달·원격 실패 판정은 각 Provider가 맡는다.
	 */
	protected static <T> T runOperation(AbstractThreadedExecution<T> operation) throws Exception {
		try {
			return operation.run();
		}
		catch ( ExecutionException e ) {
			if ( e.getCause() instanceof Exception cause ) { throw cause; }
			throw e;
		}
	}

	/** AAS 자세의 모든 성분을 검사하여 null/NaN이 primitive double의 0으로 바뀌는 것을 막는다. */
	protected final Pose readPose(Map<String, SubmodelElement> inputs, String name) throws IOException {
		JsonNode pose = Ros2SerDes.POSE_SERDE.toJsonNode(getRequiredSMCVariable(inputs, name));
		for ( String part : new String[]{"position", "orientation"} ) {
			for ( String axis : part.equals("position") ? new String[]{"x", "y", "z"} : new String[]{"x", "y", "z", "w"} ) {
				JsonNode value = pose.path(part).path(axis);
				Preconditions.checkArgument(value.isNumber() && Double.isFinite(value.doubleValue()),
						name + "." + part + "." + axis + " must be a finite number");
			}
		}
		return Pose.fromJson(pose);
	}

	/** 정착 상한(초)을 가장 가까운 밀리초로 반올림한다. 비유한 값·음수·long 범위 초과는 거부한다. */
	protected static Duration settleDuration(double seconds) {
		return secondsToDuration(seconds, "SettleTimeout");
	}

	/**
	 * 초 단위 입력을 가장 가까운 밀리초로 반올림한다. 비유한 값·음수·long 범위 초과는 거부한다.
	 *
	 * @param seconds	초 단위 값.
	 * @param name		오류 메시지에 쓸 입력 변수 이름.
	 * @return	변환된 기간.
	 */
	protected static Duration secondsToDuration(double seconds, String name) {
		Preconditions.checkArgument(Double.isFinite(seconds) && seconds >= 0, name + " must be finite and nonnegative");
		try {
			return Duration.ofMillis(BigDecimal.valueOf(seconds).movePointRight(3)
					.setScale(0, RoundingMode.HALF_UP).longValueExact());
		}
		catch ( ArithmeticException e ) {
			throw new IllegalArgumentException(name + " is out of millisecond range", e);
		}
	}

	/**
	 * 선택 진단 출력인 scene 물체 목록을 변환한다. 변환할 수 없으면(AAS idShort로 쓸 수 없는 물체 이름 등)
	 * 경고 로그만 남기고 {@code null}을 반환한다. 로봇 동작이나 관측을 이미 마친 뒤 선택 출력 하나 때문에
	 * 연산 전체를 실패시키지 않기 위해서다. 호출자는 {@code null}이면 기존 출력을 그대로 둔다.
	 *
	 * @param objects	scene_objects 값의 {@code objects} 맵.
	 * @param logger	Provider의 로거.
	 * @return	물체 SMC 목록. 변환할 수 없으면 {@code null}.
	 */
	protected @Nullable List<SubmodelElement> sceneObjectsOrNull(JsonNode objects, Logger logger) {
		try {
			return SceneObjects.fromSceneObjectMap(objects);
		}
		catch ( IOException e ) {
			logger.warn("scene objects output left unchanged: twin={}, cause={}", m_rtConfig.getTwinId(), e.getMessage());
			return null;
		}
	}

	/** JSON null과 누락된 출력을 동일하게 처리한다. */
	protected static JsonNode nonNull(JsonNode value) {
		return value != null && !value.isNull() ? value : null;
	}

	/** 순차 연산의 중단 위치. 단계 사이에서 중단했으면 failedStage는 {@code null}이다. */
	record SequentialStop<S extends Enum<S>>(S failedStage, String context) { }

	/**
	 * 예외로 끝난 순차 연산(Grasp·Place)의 중단 위치를 진행 스냅샷에서 구분한다.
	 * 성공한 마지막 단계를 실패 단계로 오인하지 않도록 단계 사이 중단을 따로 표시한다.
	 *
	 * @param steps			진행 스냅샷의 단계 결과 (실행 순서).
	 * @param activeStage	실행 중이던 단계. 없으면 {@code null}.
	 * @param stages		이번 실행에 포함된 전체 단계 (실행 순서). 선택 단계를 뺀 실행은 그 단계를 빼고 넘긴다.
	 * @return	중단 위치.
	 */
	static <S extends Enum<S>> SequentialStop<S> describeStop(List<StepResult<S>> steps, S activeStage, S[] stages) {
		if ( activeStage != null ) { return new SequentialStop<>(activeStage, activeStage + " 실행 중 중단"); }
		if ( steps.isEmpty() ) { return new SequentialStop<>(null, stages[0] + " 진입 전 중단"); }
		StepResult<S> last = steps.get(steps.size() - 1);
		if ( !last.goalReached() ) { return new SequentialStop<>(last.stage(), last.stage() + " 목표 미확인 또는 실행 중단"); }
		int index = Arrays.asList(stages).indexOf(last.stage());
		if ( index == stages.length - 1 ) { return new SequentialStop<>(null, "모든 단계 목표 확인 후 중단"); }
		S next = stages[index + 1];
		return new SequentialStop<>(null, last.stage() + " 완료 후 " + next + " 진입 전 중단");
	}

	/**
	 * 비동기 이동 연산의 완료를 기다릴 클라이언트 타임아웃을 계산한다.
	 * <p>
	 * 로봇 트윈은 세션의 시간 상한({@code max_duration_sec}, 없으면 연산의 {@code default_timeout_sec})을
	 * 넘기면 로봇에 정지를 보내고 세션을 {@code FAILED}({@code TIMEOUT})로 종료한다. 클라이언트 타임아웃이
	 * 이 상한과 같거나 짧으면 클라이언트가 먼저 대기를 포기하여 로봇 트윈의 판정을 받지 못하고, 원격 세션은
	 * 취소되지 않은 채 로봇이 계속 움직인다. 그래서 상한에 여유 시간을 더한 값을 사용한다.
	 *
	 * @param maxDurationSec	요청에 담은 {@code max_duration_sec} (초). 지정하지 않았으면 {@code null}.
	 * @return	클라이언트 타임아웃.
	 */
	protected static Duration getClientTimeout(Integer maxDurationSec) {
		Duration limit = (maxDurationSec != null) ? Duration.ofSeconds(maxDurationSec) : TWIN_DEFAULT_TIME_LIMIT;
		return limit.plus(CLIENT_TIMEOUT_MARGIN);
	}

	/** 원격 실패 안내를 Provider의 로거에 남기고, AAS 출력 갱신 전에 예외로 전달한다. */
	protected void throwIfRemoteFailed(OperationResult result, String why, Logger logger) {
		if ( !result.isCompleted() ) {
			logger.warn("robot operation failed: twin={}, operation={}, status={}, code={}, details={}",
					m_rtConfig.getTwinId(), result.operation(), result.status(), result.errorCode(), why);
			throw new OperationFailedException(result);
		}
	}

	/**
	 * 관절·이름 자세 이동의 결과를 처리한다. 실패는 출력 갱신 전에 예외로 전달한다.
	 * 도달 확인 불가는 로그만 남기며, 최종 출력은 정착을 검증하지 않은 서버 스냅샷이다.
	 * Provider의 로거를 받아 기존 로거 이름과 필터 설정을 유지한다.
	 */
	protected void handleMotionResult(OperationResult result, boolean reached, String why, Logger logger,
			SubmodelElementCollection finalPoseSmc, SubmodelElementCollection finalJointsSmc) throws IOException {
		throwIfRemoteFailed(result, why, logger);
		if ( !reached ) {
			JsonNode closedLoop = result.output("closed_loop");
			boolean openLoop = closedLoop != null && closedLoop.isBoolean() && !closedLoop.booleanValue();
			logger.atLevel(openLoop ? Level.INFO : Level.WARN)
					.log("target arrival could not be confirmed: twin={}, operation={}, details={}; "
							+ "FinalPose and FinalJoints are remote snapshots, not evidence of settling",
							m_rtConfig.getTwinId(), result.operation(), why);
		}
		updateFinalState(finalPoseSmc, nonNull(result.output("final_pose")),
				finalJointsSmc, nonNull(result.output("final_joints")));
	}

	/**
	 * 이동 연산의 최종 자세와 관절 상태로 {@code FinalPose}/{@code FinalJoints} 출력 변수를 갱신한다.
	 * <p>
	 * 로봇 트윈은 상태 캐시가 비어 있으면 {@code final_pose}/{@code final_joints}를 연산 출력에서 아예 뺀다.
	 * 이 경우 로봇은 이미 움직인 뒤이므로 연산을 실패시키지 않고, 경고만 남긴 채 해당 출력 변수를 갱신하지 않는다.
	 *
	 * @param finalPoseSmc		갱신할 {@code FinalPose} 출력 변수.
	 * @param finalPose			최종 자세({@code geometry_msgs/PoseStamped} JSON). 없으면 {@code null}.
	 * @param finalJointsSmc	갱신할 {@code FinalJoints} 출력 변수.
	 * @param finalJoints		최종 관절 상태 JSON. 없으면 {@code null}.
	 * @throws IOException	값의 형식이 잘못된 경우.
	 */
	protected void updateFinalState(SubmodelElementCollection finalPoseSmc, JsonNode finalPose,
									SubmodelElementCollection finalJointsSmc, JsonNode finalJoints)
		throws IOException {
		if ( finalPose != null ) {
			Ros2SerDes.POSE_STAMPED_SERDE.updateSubmodelElement(finalPoseSmc, finalPose);
		}
		else {
			s_logger.warn("final pose is not reported, FinalPose is not updated: op={}, twin={}",
							getClass().getSimpleName(), m_rtConfig.getTwinId());
		}
		if ( finalJoints != null ) {
			Ros2SerDes.JOINT_STATES_SERDE.updateSubmodelElement(finalJointsSmc, finalJoints);
		}
		else {
			s_logger.warn("final joints are not reported, FinalJoints is not updated: op={}, twin={}",
							getClass().getSimpleName(), m_rtConfig.getTwinId());
		}
	}

}
