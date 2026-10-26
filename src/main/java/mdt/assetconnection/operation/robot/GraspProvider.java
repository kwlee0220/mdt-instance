package mdt.assetconnection.operation.robot;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.Preconditions;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.Grasp;
import mdt.robot.client.operation.MoveLinear;
import mdt.robot.client.operation.StepResult;

/**
 * 클라이언트 Grasp로 열기 → 접근 → 하강 → 파지 목표 확인을 수행한다. 상승은 포함하지 않는다.
 * 두 pose는 서버 기본 EE 프레임 기준 절대 자세다. 물체 중심의 TCP 보정이나 접근 높이 계산은 호출자가 한다.
 * 이동은 위치만 비교하며 GoalsReached=true도 특정 물체의 실제 파지를 보장하지 않는다.
 * <p>
 * 모든 입력과 출력 바인딩을 첫 개방 전에 검증한다. 단계 목표 미확인(원격 FAILED/CANCELLED 포함)은
 * GoalsReached=false와 단계 진단을 정상 반환한다. 워크플로는 반드시 GoalsReached로 다음 동작을 결정한다.
 * 접수·통신 오류는 원인 예외, 인터럽트는 InterruptedException으로 전달한다. 예외 시 진행 진단은
 * 로그로 남지만 MDTOperationProvider가 출력을 반환하지 않아 워크플로에서는 읽을 수 없다.
 * 입력 검증 실패 시에는 기존 출력이 유지된다. 실행을 시작하면 기존 진단을 초기화한다.
 * <p>
 * GraspAttempted는 GRASP 하위 실행 진입 기록이다. 전송·파지 성공의 증거가 아니며 false도 빈손을 보장하지 않는다.
 * 자동 재시도·개방·복귀·상승은 하지 않는다. 전체 단계 동안 원격 자원을 묶어서 점유하지 않으므로
 * 호출자가 다른 명령과의 경합을 방지해야 한다. Provider는 동기 run()을 사용하며 별도의 취소 API는 없다.
 * <dl>
 * <dt>입력</dt><dd>ApproachPose, GraspPose (SMC, 필수); ApproachVelocityScaling, DescentVelocityScaling
 * (xs:float, 기본 각각 0.2); Tolerance (xs:double, m, 기본 0.005); SettleTimeout (xs:double, 초, 기본 10,
 * 밀리초로 반올림하며 0이면 정착 확인 생략); TimeoutSec (xs:int, 기본 120, 하위 비동기 연산별 대기 상한);
 * OpenTarget, GraspTarget (xs:string, 기본 open/grasp).</dd>
 * <dt>출력</dt><dd>GoalsReached, GraspAttempted (xs:boolean), FailedStage, FailureReason (xs:string), 모두 필수.
 * GoalsReached=true이면 두 문자열은 빈 값이다. 실패 단계는 OPEN/APPROACH/DESCEND/GRASP다.</dd>
 * </dl>
 * TimeoutSec는 서버 실행 상한이나 전체 Grasp 상한이 아니다. 동기 그리퍼에는 HTTP 읽기 제한 30초가,
 * 개루프 이동에는 각 단계의 SettleTimeout이 별도로 적용된다.
 */
public class GraspProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(GraspProvider.class);

	private final RobotTwinClient m_twin;

	public GraspProvider(ServiceContext serviceContext, Reference operationRef, JavaOperationProviderConfig config)
			throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		m_twin = createRobotTwinClient();
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
								Map<String, SubmodelElement> inoutputVarList,
								Map<String, SubmodelElement> outputVarList) throws Exception {
		Property reached = getRequiredPropertyVariable(outputVarList, "GoalsReached", DataTypeDefXsd.BOOLEAN);
		Property attempted = getRequiredPropertyVariable(outputVarList, "GraspAttempted", DataTypeDefXsd.BOOLEAN);
		Property failedStage = getRequiredPropertyVariable(outputVarList, "FailedStage", DataTypeDefXsd.STRING);
		Property reason = getRequiredPropertyVariable(outputVarList, "FailureReason", DataTypeDefXsd.STRING);
		Pose approach = readPose(inputVarList, "ApproachPose");
		Pose grasp = readPose(inputVarList, "GraspPose");
		Double tolerance = getDoubleVariableValue(inputVarList, "Tolerance");
		tolerance = tolerance != null ? tolerance : 0.005;
		Preconditions.checkArgument(Double.isFinite(tolerance) && tolerance > 0, "Tolerance must be finite and positive");
		Double settle = getDoubleVariableValue(inputVarList, "SettleTimeout");
		Duration settleTimeout = settleDuration(settle != null ? settle : 10.0);
		Integer timeout = getIntVariableValue(inputVarList, "TimeoutSec");
		timeout = timeout != null ? timeout : 120;
		Preconditions.checkArgument(timeout > 0, "TimeoutSec must be positive");
		String openTarget = getStringVariableValue(inputVarList, "OpenTarget");
		String graspTarget = getStringVariableValue(inputVarList, "GraspTarget");
		Grasp.Options options = new Grasp.Options(
				new MoveLinear.Options(tolerance, speed(inputVarList, "ApproachVelocityScaling"), settleTimeout),
				new MoveLinear.Options(tolerance, speed(inputVarList, "DescentVelocityScaling"), settleTimeout),
				openTarget != null ? openTarget : "open", graspTarget != null ? graspTarget : "grasp");
		Grasp operation = new Grasp(m_twin, approach, grasp, options, Duration.ofSeconds(timeout));
		writeOutputs(reached, attempted, failedStage, reason, false, false, null, null);
		Grasp.Check check;
		try {
			check = runOperation(operation);
		}
		catch ( Exception e ) {
			Grasp.Progress progress = operation.getProgress();
			var steps = progress.steps();
			SequentialStop<Grasp.Stage> stopped = describeStop(steps, progress.activeStage().orElse(null));
			Grasp.Stage stage = stopped.failedStage();
			String details = stopped.context() + ": " + e;
			writeOutputs(reached, attempted, failedStage, reason, false, progress.graspAttempted(), stage, details);
			s_logger.warn("grasp interrupted or failed: twin={}, stage={}, graspAttempted={}, details={}",
					m_rtConfig.getTwinId(), stage, progress.graspAttempted(), details);
			throw e;
		}
		writeOutputs(reached, attempted, failedStage, reason, check.goalsReached(), check.graspAttempted(),
				check.failedStage(), check.why());
		if ( !check.goalsReached() ) {
			s_logger.warn("grasp goal not confirmed: twin={}, stage={}, graspAttempted={}, details={}",
					m_rtConfig.getTwinId(), check.failedStage(), check.graspAttempted(), check.why());
		}
	}

	/** 성공한 마지막 단계를 실패 단계로 오인하지 않도록 실행 중단 위치를 구분한다. */
	static SequentialStop<Grasp.Stage> describeStop(List<StepResult<Grasp.Stage>> steps, Grasp.Stage activeStage) {
		return describeStop(steps, activeStage, Grasp.Stage.values());
	}

	private Double speed(Map<String, SubmodelElement> inputs, String name) {
		Float value = getFloatVariableValue(inputs, name);
		return value != null ? Double.valueOf(value.toString()) : 0.2;
	}

	private static void writeOutputs(Property reached, Property attempted, Property stage, Property reason,
			boolean goalsReached, boolean graspAttempted, Grasp.Stage failedStage, String why) {
		reached.setValue(Boolean.toString(goalsReached));
		attempted.setValue(Boolean.toString(graspAttempted));
		stage.setValue(failedStage != null ? failedStage.name() : "");
		reason.setValue(why != null ? why : "");
	}
}
