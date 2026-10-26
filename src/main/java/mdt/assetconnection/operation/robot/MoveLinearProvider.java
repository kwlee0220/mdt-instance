package mdt.assetconnection.operation.robot;

import java.time.Duration;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.Preconditions;
import utils.http.RESTfulException;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.TwinException;
import mdt.robot.client.operation.MoveCheck;
import mdt.robot.client.operation.MoveLinear;
import mdt.task.TaskException;


/**
 * 로봇 트윈의 {@code move_linear} 연산을 호출하여 팔 끝을 목표 자세까지 직선 경로로 이동시키고,
 * 실제로 목표에 도달했는지를 판정한다.
 * <p>
 * 로봇 트윈의 {@code COMPLETED}는 연산이 종료되었다는 뜻일 뿐 목표 도달을 보장하지 않는다.
 * 직선 경로 계획이 일부만 성공해도 그 부분만 실행한 뒤 성공으로 종료하고, 개루프 백엔드에서는
 * 종료 시점에 로봇이 아직 움직이는 중일 수 있다. 요청 실행과 도달 판정은 mdt-robot-client의 {@link MoveLinear}에 위임한다.
 * <ol>
 *   <li>정착 상한이 양수이고 {@code closed_loop}이 JSON Boolean true가 아니면 {@code ee_pose}가 멈출 때까지
 *       기다린 뒤 그 자세를 최종 자세로 사용한다. 대기 시간의 상한은 {@code SettleTimeout}이다.
 *       정착을 확인하지 못하면 Reached=false이며 이전 최종 자세로 성공을 판정하지 않는다.</li>
 *   <li>최종 자세와 목표 자세의 위치 오차(축별 거리의 최대값)를 {@code Tolerance}와 비교한다.
 *       자세(orientation)는 비교하지 않는다.</li>
 * </ol>
 * 목표는 서버 기본 EE 프레임(미설정 시 tip 프레임) 기준으로 전달한다. TargetPose와 ee_pose의
 * 좌표계·도구 기준점은 같아야 한다. Frame, MaxStep, JumpThreshold 입력은 더 이상 지원하지 않으며,
 * 값이 비어 있어도 변수가 전달되면 원격 요청 전에 거부한다. 해당 설정은 서버에서 관리한다.
 * 최종 위치를 판정할 수 없으면 Reached=false이고 PositionError는 갱신하지 않는다.
 * 기본적으로 목표에 도달하지 못한 것은 연산 실패로 처리하지 않고 {@code Reached}로 알린다. 접촉이
 * 예상되는 구간처럼 도달하지 못하는 것이 정상인 경우가 있기 때문이다. {@code RequireReached}가
 * {@code true}이면 도달하지 못했을 때(판정할 수 없는 경우 포함) 출력 변수를 갱신한 뒤
 * {@link TaskException}으로 연산을 실패시킨다. 워크플로에서 별도의 판정 task 없이 그 자리에서
 * 멈추게 할 때 쓴다. 예외 시 MDTOperationProvider는 출력을 반환하지 않으므로 실패 시점의
 * 자세·판정을 워크플로에서 읽으려면 RequireReached=false로 호출하고 Reached로 분기해야 한다.
 * SettleTimeout은 밀리초로 반올림하며, TargetPose의 모든 성분은 유한한 숫자여야 한다.
 * 로봇 트윈이 연산을 {@code FAILED} 또는 {@code CANCELLED}로 종료한 경우는 항상 예외가 발생한다.
 * 정착에 성공한 경우 FinalPose에는 그 관측값을 사용하고, FinalJoints는 품질이 OK인 새 조회값이
 * 있으면 갱신한다. 관절 품질 불량·값 누락·조회 통신 오류는 경고를 남기고 서버 관절 출력을 유지한다.
 * 이 경우에도 확보한 정착 자세와 도달 판정은 출력하며, 조회 인터럽트는 계속 전파한다.
 * FinalPose와 FinalJoints는 동기화된 표본이 아니며 품질 OK만으로 관측 시점 일치를 보장하지 않는다.
 * 정착 실패 시 FinalPose/FinalJoints는 서버 출력이 있으면 보관하지만 도달 근거로 쓰지 않는다.
 * 서버의 max_duration_sec와 클라이언트 대기 시간(서버 제한 + 30초)은 기존 규칙을 유지한다.
 * Provider는 동기 run()을 사용하며 별도의 취소 API나 자동 원격 취소를 추가하지 않는다.
 *
 * <dl>
 *   <dt>입력 변수</dt>
 *   <dd>{@code TargetPose} (SMC, 필수), {@code VelocityScaling} (xs:float, 선택),
 *       {@code MaxDurationSec} (xs:int, 선택, 단위 초, 0보다 커야 함),
 *       {@code Tolerance} (xs:double, 선택, 단위 m, 기본값 0.005),
 *       {@code SettleTimeout} (xs:double, 선택, 단위 초, 기본값 10, 0이면 정착 대기를 생략),
 *       {@code RequireReached} (xs:boolean, 선택, 기본값 false)</dd>
 *   <dt>출력 변수</dt>
 *   <dd>{@code FinalPose}, {@code FinalJoints} (SMC, 필수),
 *       {@code Reached} (xs:boolean, 선택), {@code PositionError} (xs:double, 선택, 단위 m)</dd>
 * </dl>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MoveLinearProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(MoveLinearProvider.class);

	private static final double DEFAULT_TOLERANCE = 0.005;
	private static final double DEFAULT_SETTLE_TIMEOUT_SECS = 10;

	private final RobotTwinClient m_twin;

	public MoveLinearProvider(ServiceContext serviceContext, Reference operationRef,
							JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		m_twin = createRobotTwinClient();
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
							Map<String, SubmodelElement> inoutputVarList,
							Map<String, SubmodelElement> outputVarList) throws Exception {
		for ( String removed : new String[] { "Frame", "MaxStep", "JumpThreshold" } ) {
			Preconditions.checkArgument(!inputVarList.containsKey(removed) && !inoutputVarList.containsKey(removed),
					removed + " is no longer supported; remove this input and use the server configuration");
		}
		SubmodelElementCollection finalPoseSmc = getRequiredSMCVariable(outputVarList, "FinalPose");
		SubmodelElementCollection finalJointsSmc = getRequiredSMCVariable(outputVarList, "FinalJoints");
		Property reachedProp = getPropertyVariable(outputVarList, "Reached", DataTypeDefXsd.BOOLEAN);
		Property posErrorProp = getPropertyVariable(outputVarList, "PositionError", DataTypeDefXsd.DOUBLE);

		// 판정 조건도 로봇을 움직이기 전에 검증한다.
		Double tolerance = getDoubleVariableValue(inputVarList, "Tolerance");
		tolerance = (tolerance != null) ? tolerance : DEFAULT_TOLERANCE;
		Preconditions.checkArgument(Double.isFinite(tolerance) && tolerance > 0, "Tolerance must be positive: " + tolerance);

		Double settleSecs = getDoubleVariableValue(inputVarList, "SettleTimeout");
		settleSecs = (settleSecs != null) ? settleSecs : DEFAULT_SETTLE_TIMEOUT_SECS;
		Duration settleTimeout = settleDuration(settleSecs);

		Pose target = readPose(inputVarList, "TargetPose");
		Float vs = getFloatVariableValue(inputVarList, "VelocityScaling");
		Integer maxDuration = getIntVariableValue(inputVarList, "MaxDurationSec");
		if ( maxDuration != null ) {
			Preconditions.checkArgument(maxDuration > 0, "MaxDurationSec must be positive: " + maxDuration);
		}
		boolean requireReached = Boolean.TRUE.equals(getBooleanVariableValue(inputVarList, "RequireReached"));

		MoveLinear operation = MoveLinear.builder(m_twin, target)
				.velocityScaling(vs != null ? Double.valueOf(vs.toString()) : null)
				.maxDuration(maxDuration != null ? Duration.ofSeconds(maxDuration) : null)
				.tolerance(tolerance).settleTimeout(settleTimeout)
				.timeout(getClientTimeout(maxDuration)).build();
		MoveCheck check = runOperation(operation);
		throwIfRemoteFailed(check.result(), check.why(), s_logger);

		JsonNode finalPose = nonNull(check.result().output("final_pose"));
		JsonNode finalJoints = nonNull(check.result().output("final_joints"));
		if ( check.settledPose() != null ) {
			finalPose = check.settledPose();
			try {
				JsonNode joints = m_twin.readRaw("joint_states");
				if ( "OK".equals(joints.path("quality").asText()) && joints.hasNonNull("value") ) {
					finalJoints = joints.get("value");
				}
				else {
					s_logger.warn("settled joint observation unavailable: twin={}, quality={}, reason={}, "
							+ "using remote final_joints if present; FinalPose and FinalJoints may differ in time",
							m_rtConfig.getTwinId(), joints.path("quality").asText(), joints.path("reason").asText());
				}
			}
			catch ( TwinException | RESTfulException e ) {
				// 부가 조회의 실패로 확보한 도달 판정을 잃지 않는다. 인터럽트는 삼키지 않는다.
				if ( Thread.currentThread().isInterrupted() ) { throw e; }
				s_logger.warn("settled joint observation failed: twin={}, using remote final_joints if present; "
						+ "FinalPose and FinalJoints may differ in time", m_rtConfig.getTwinId(), e);
			}
		}

		boolean reached = check.reached();
		Double posError = check.errorM();
		if ( MoveCheck.SETTLE_TIMEOUT.equals(check.reason()) ) {
			s_logger.warn("robot settling could not be confirmed: twin={}, details={}",
					m_rtConfig.getTwinId(), check.why());
		}
		if ( posErrorProp != null && posError != null ) {
			posErrorProp.setValue(Double.toString(posError));
		}
		updateFinalState(finalPoseSmc, finalPose, finalJointsSmc, finalJoints);
		if ( reachedProp != null ) {
			reachedProp.setValue(Boolean.toString(reached));
		}

		// 출력 변수를 모두 갱신한 뒤에 실패시켜, 실패한 시점의 자세를 확인할 수 있게 한다.
		if ( requireReached && !reached ) {
			String details = (posError != null)
							? String.format("target is not reached: error=%.2fmm, tolerance=%.2fmm",
											posError * 1000, tolerance * 1000)
							: (check.why() != null ? check.why() : "final pose is not reported, cannot judge reaching");
			throw new TaskException(details);
		}
	}
}
