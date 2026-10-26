package mdt.assetconnection.operation.robot;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.Preconditions;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.model.sm.SubmodelUtils;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.MeasureObjectOffset;
import mdt.robot.client.operation.MoveLinear;
import mdt.robot.client.operation.Pick;
import mdt.robot.client.operation.StepResult;

/**
 * 클라이언트 Pick으로 열기 → 접근 → 하강 → 파지 → 들어 올리기를 수행한다. GraspProvider에 상승을 더한 것이다.
 * 세 pose는 서버 기본 EE 프레임 기준 절대 자세다. 물체 중심의 TCP 보정이나 접근 높이 계산은 호출자가 한다.
 * <p>
 * ObjectName을 주면 파지 직후와 들어 올린 뒤에 물린 위치(물체 중심 - 도구 기준점)를 재고, 두 값을 도구 좌표계에서
 * 비교한 차이가 HoldTolerance를 넘으면 GoalsReached=false(FailedStage=MEASURE_HELD)다. 도구 좌표계에서 비교하므로
 * 들어 올리는 동안 손목이 돌아도 단단히 쥔 물체는 실패로 보지 않는다. 그리퍼의 파지 판정은 원하는 물체를
 * 들었는지까지 판정하지 않으므로, 물체가 탁자에 남았거나 다른 물체를 문 경우를 이 장면 관측으로 걸러 낸다.
 * 두 측정 사이에 도구 기준점이 실제로 HoldTolerance의 2배 이상 움직이지 않았으면(상승이 짧거나 상승 뒤 관측이
 * 갱신되지 않았으면) 유지를 판정할 수 없으므로 같은 실패로 반환하며, LiftPose가 GraspPose에서 그보다 가까우면
 * 움직이기 전에 IllegalArgumentException으로 거부한다.
 * ObjectName이 없거나 빈 값이면 재지 않으며 GoalsReached=true도 실제 파지를 보장하지 않는다.
 * <p>
 * 모든 입력과 출력 바인딩을 첫 개방 전에 검증한다. 단계 목표 미확인(원격 FAILED/CANCELLED 포함)과 유지 판정
 * 실패는 GoalsReached=false와 단계 진단을 정상 반환한다. 워크플로는 반드시 GoalsReached로 다음 동작을 결정한다.
 * 접수·통신 오류는 원인 예외, 인터럽트는 InterruptedException으로 전달한다. 예외 시 진행 진단은
 * 로그로 남지만 MDTOperationProvider가 출력을 반환하지 않아 워크플로에서는 읽을 수 없다.
 * 입력 검증 실패 시에는 기존 출력이 유지된다. 실행을 시작하면 기존 진단을 초기화한다.
 * <p>
 * GraspAttempted는 GRASP 하위 실행 진입 기록이다. 자동 재시도·개방·복귀는 하지 않으며, 유지 판정에 실패해도
 * 들어 올린 자세에서 그리퍼를 닫은 채로 멈춘다. 전체 단계 동안 원격 자원을 묶어서 점유하지 않으므로
 * 호출자가 다른 명령과의 경합을 방지해야 한다. Provider는 동기 run()을 사용하며 별도의 취소 API는 없다.
 * <dl>
 * <dt>입력</dt><dd>ApproachPose, GraspPose (SMC, 필수); LiftPose (SMC, 없으면 ApproachPose);
 * ApproachVelocityScaling, DescentVelocityScaling, LiftVelocityScaling (xs:float, 기본 각각 0.2);
 * Tolerance (xs:double, m, 기본 0.005); SettleTimeout (xs:double, 초, 기본 10, 밀리초로 반올림하며 0이면
 * 정착 확인 생략); TimeoutSec (xs:int, 기본 120, 하위 비동기 연산별 대기 상한); OpenTarget, GraspTarget
 * (xs:string, 기본 open/grasp); ObjectName (xs:string, 선택); TcpReference (xs:string, 기본 AS_REPORTED.
 * 펑션베이는 AS_REPORTED, Isaac Panda Hand는 PANDA_HAND_TCP); HoldTolerance (xs:double, m, 기본 0.01).</dd>
 * <dt>출력</dt><dd>GoalsReached, GraspAttempted (xs:boolean), FailedStage, FailureReason (xs:string), 모두 필수.
 * GoalsReached=true이면 두 문자열은 빈 값이다. 실패 단계는 OPEN/APPROACH/DESCEND/GRASP/MEASURE_GRIP/LIFT/
 * MEASURE_HELD다. 선택 출력 HoldDriftM (xs:double, 들기 전후 물린 위치를 도구 좌표계에서 비교한 차이),
 * HeldOffset (SMC x/y/z, 들어 올린 뒤의 물린 위치, 기준 좌표계), Objects (SMC, 들어 올린 뒤 측정의 scene 물체)는
 * 두 측정을 모두 마쳤을 때만 갱신하며, 그렇지 않으면 이전 값이 남는다. Objects는 AAS idShort로 쓸 수 없는 물체
 * 이름 등으로 변환할 수 없으면 경고 로그만 남기고 이전 값을 둔다. 나머지 출력은 정상 반환한다.</dd>
 * </dl>
 * TimeoutSec는 서버 실행 상한이나 전체 Pick 상한이 아니다. 동기 그리퍼에는 HTTP 읽기 제한 30초가,
 * 개루프 이동에는 각 단계의 SettleTimeout이, 측정에는 측정별 5초 상한이 별도로 적용된다.
 */
public class PickProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(PickProvider.class);
	private static final Pick.Stage[] WITHOUT_MEASUREMENT = {
		Pick.Stage.OPEN, Pick.Stage.APPROACH, Pick.Stage.DESCEND, Pick.Stage.GRASP, Pick.Stage.LIFT
	};

	private final RobotTwinClient m_twin;

	public PickProvider(ServiceContext serviceContext, Reference operationRef, JavaOperationProviderConfig config)
			throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		m_twin = createRobotTwinClient();
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList, Map<String, SubmodelElement> inoutputVarList,
			Map<String, SubmodelElement> outputVarList) throws Exception {
		Property reached = getRequiredPropertyVariable(outputVarList, "GoalsReached", DataTypeDefXsd.BOOLEAN);
		Property attempted = getRequiredPropertyVariable(outputVarList, "GraspAttempted", DataTypeDefXsd.BOOLEAN);
		Property failedStage = getRequiredPropertyVariable(outputVarList, "FailedStage", DataTypeDefXsd.STRING);
		Property reason = getRequiredPropertyVariable(outputVarList, "FailureReason", DataTypeDefXsd.STRING);
		Property driftM = getPropertyVariable(outputVarList, "HoldDriftM", DataTypeDefXsd.DOUBLE);
		SubmodelElementCollection heldOffset = getSMCVariable(outputVarList, "HeldOffset");
		SubmodelElementCollection objects = getSMCVariable(outputVarList, "Objects");

		Pose approach = readPose(inputVarList, "ApproachPose");
		Pose grasp = readPose(inputVarList, "GraspPose");
		Pose lift = inputVarList.containsKey("LiftPose") ? readPose(inputVarList, "LiftPose") : approach;
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
		String objectName = getStringVariableValue(inputVarList, "ObjectName");
		objectName = (objectName != null && !objectName.isBlank()) ? objectName : null;
		MeasureObjectOffset.TcpReference tcpReference = tcpReference(getStringVariableValue(inputVarList, "TcpReference"));
		Double holdTolerance = getDoubleVariableValue(inputVarList, "HoldTolerance");
		Pick.Options options = new Pick.Options(
				new MoveLinear.Options(tolerance, speed(inputVarList, "ApproachVelocityScaling"), settleTimeout),
				new MoveLinear.Options(tolerance, speed(inputVarList, "DescentVelocityScaling"), settleTimeout),
				new MoveLinear.Options(tolerance, speed(inputVarList, "LiftVelocityScaling"), settleTimeout),
				openTarget != null ? openTarget : "open", graspTarget != null ? graspTarget : "grasp",
				MeasureObjectOffset.Options.DEFAULT, holdTolerance != null ? holdTolerance : 0.01);
		Pick operation = new Pick(m_twin, approach, grasp, lift, objectName, tcpReference, options, Duration.ofSeconds(timeout));
		writeOutputs(reached, attempted, failedStage, reason, false, false, null, null);
		Pick.Check check;
		try {
			check = runOperation(operation);
		}
		catch ( Exception e ) {
			Pick.Progress progress = operation.getProgress();
			SequentialStop<Pick.Stage> stopped = describeStop(progress.steps(), progress.activeStage().orElse(null),
																objectName != null);
			Pick.Stage stage = stopped.failedStage();
			String details = stopped.context() + ": " + e;
			writeOutputs(reached, attempted, failedStage, reason, false, progress.graspAttempted(), stage, details);
			s_logger.warn("pick interrupted or failed: twin={}, stage={}, graspAttempted={}, details={}",
					m_rtConfig.getTwinId(), stage, progress.graspAttempted(), details);
			throw e;
		}

		// 일부 출력만 갱신되지 않도록, 변환을 모두 끝낸 뒤에 반영한다.
		List<SubmodelElement> sceneObjects = null;
		if ( objects != null && check.hold().isPresent() ) {
			sceneObjects = sceneObjectsOrNull(check.hold().get().afterLift().sceneSample().path("value").get("objects"), s_logger);
		}
		if ( check.hold().isPresent() ) {
			Pick.Hold hold = check.hold().get();
			if ( driftM != null ) {
				driftM.setValue(Double.toString(hold.driftM()));
			}
			if ( heldOffset != null ) {
				heldOffset.setValue(toElements(hold.afterLift().offset()));
			}
			if ( sceneObjects != null ) {
				objects.setValue(sceneObjects);
			}
		}
		writeOutputs(reached, attempted, failedStage, reason, check.goalsReached(), check.graspAttempted(),
				check.failedStage(), check.why());
		if ( !check.goalsReached() ) {
			s_logger.warn("pick goal not confirmed: twin={}, object={}, stage={}, graspAttempted={}, details={}",
					m_rtConfig.getTwinId(), objectName, check.failedStage(), check.graspAttempted(), check.why());
		}
	}

	/** 성공한 마지막 단계를 실패 단계로 오인하지 않도록 실행 중단 위치를 구분한다. */
	static SequentialStop<Pick.Stage> describeStop(List<StepResult<Pick.Stage>> steps, Pick.Stage activeStage,
													boolean measured) {
		return describeStop(steps, activeStage, measured ? Pick.Stage.values() : WITHOUT_MEASUREMENT);
	}

	private static MeasureObjectOffset.TcpReference tcpReference(String value) {
		if ( value == null || value.isBlank() ) {
			return MeasureObjectOffset.TcpReference.AS_REPORTED;
		}
		try {
			return MeasureObjectOffset.TcpReference.valueOf(value.trim());
		}
		catch ( IllegalArgumentException e ) {
			throw new IllegalArgumentException("TcpReference must be AS_REPORTED or PANDA_HAND_TCP: " + value, e);
		}
	}

	private Double speed(Map<String, SubmodelElement> inputs, String name) {
		Float value = getFloatVariableValue(inputs, name);
		return value != null ? Double.valueOf(value.toString()) : 0.2;
	}

	private static List<SubmodelElement> toElements(Position p) {
		return List.of(SubmodelUtils.newProperty("x", Double.toString(p.x()), DataTypeDefXsd.DOUBLE),
						SubmodelUtils.newProperty("y", Double.toString(p.y()), DataTypeDefXsd.DOUBLE),
						SubmodelUtils.newProperty("z", Double.toString(p.z()), DataTypeDefXsd.DOUBLE));
	}

	private static void writeOutputs(Property reached, Property attempted, Property stage, Property reason,
			boolean goalsReached, boolean graspAttempted, Pick.Stage failedStage, String why) {
		reached.setValue(Boolean.toString(goalsReached));
		attempted.setValue(Boolean.toString(graspAttempted));
		stage.setValue(failedStage != null ? failedStage.name() : "");
		reason.setValue(why != null ? why : "");
	}
}
