package mdt.assetconnection.operation.robot;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.model.MDTModelSerDe;
import mdt.robot.client.Geometry;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.AlignHeldObject;
import mdt.robot.client.operation.MeasureObjectOffset;
import mdt.robot.client.operation.MoveLinear;
import mdt.robot.client.operation.StepResult;
import mdt.robot.client.operation.WaitUntilObjectStill;
import mdt.rt.Ros2SerDes;

/**
 * 클라이언트 AlignHeldObject로 쥔 원기둥(peg)의 아래쪽 끝면 중심을 입구 위로 맞춘다. 하강·삽입은 포함하지 않는다.
 * 회차마다 측정 → 보정 이동 → 정착 확인을 반복하며, 첫 회차만 측정 전에 물체 정착을 먼저 기다린다.
 * <p>
 * 허용 반경은 {@code Clearance × AcceptRatio}다(Python peg_in_hole의 반경 여유 × ALIGN_MARGIN). 오차가 그 안에
 * 들어오면 Aligned=true로 멈추고, 직전 회차보다 줄지 않거나 MaxPasses에 닿으면 Aligned=false로 멈춘다.
 * 정착·측정을 확인하지 못했거나 보정 이동이 도달하지 못해도 Aligned=false(StopReason=STEP_FAILED)다.
 * 정렬 실패는 예외가 아니라 정상 반환이므로 워크플로는 반드시 Aligned로 다음 동작(하강)을 결정한다.
 * 접수·통신 오류는 원인 예외, 인터럽트는 InterruptedException으로 전달하며 이때 출력은 반환되지 않는다.
 * <p>
 * 이동 명령의 기준점과 ee_pose가 같은 점이어야 한다(펑션베이 grasp_center). 입구는 로봇 base 좌표로 주며
 * x, y만 쓴다. 보정 이동의 자세는 아래를 보는 자세를 z축으로 TargetYawDeg만큼 돌린 절대 자세다.
 * 모든 입력과 출력 바인딩을 첫 관측 전에 검증하며, 입력 검증 실패 시 기존 출력이 유지된다.
 * <dl>
 * <dt>입력</dt><dd>ObjectName (xs:string, 필수); Entry (SMC x/y/z, 필수); Clearance (xs:double, m, 필수, 양수);
 * TravelZ (xs:double, m, 필수); AcceptRatio (xs:double, 기본 0.4); TargetYawDeg (xs:double, 기본 -90);
 * MaxPasses (xs:int, 기본 3); VelocityScaling (xs:float, 기본 0.15); Tolerance (xs:double, m, 기본 0.005);
 * SettleTimeout (xs:double, 초, 기본 15, 보정 이동 정착 상한); StillTimeout (xs:double, 초, 기본 10, 물체 정착 상한);
 * TimeoutSec (xs:int, 기본 120, 보정 이동 결과 대기 상한).</dd>
 * <dt>출력</dt><dd>Aligned (xs:boolean), StopReason (xs:string, ALIGNED/NOT_IMPROVING/MAX_PASSES/STEP_FAILED),
 * Passes (xs:int)는 필수; ErrorM (xs:double, 마지막으로 잰 오차), Target (Pose SMC, 마지막 보정 목표),
 * Objects (SMC, 마지막 정착 관측의 scene 물체), FailedStage·FailureReason (xs:string)은 선택.</dd>
 * </dl>
 */
public class AlignHeldObjectProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(AlignHeldObjectProvider.class);

	private final RobotTwinClient m_twin;

	public AlignHeldObjectProvider(ServiceContext serviceContext, Reference operationRef,
									JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		m_twin = createRobotTwinClient();
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList, Map<String, SubmodelElement> inoutputVarList,
			Map<String, SubmodelElement> outputVarList) throws Exception {
		Property aligned = getRequiredPropertyVariable(outputVarList, "Aligned", DataTypeDefXsd.BOOLEAN);
		Property stopReason = getRequiredPropertyVariable(outputVarList, "StopReason", DataTypeDefXsd.STRING);
		Property passes = getRequiredPropertyVariable(outputVarList, "Passes", DataTypeDefXsd.INT);
		Property errorM = getPropertyVariable(outputVarList, "ErrorM", DataTypeDefXsd.DOUBLE);
		SubmodelElementCollection target = getSMCVariable(outputVarList, "Target");
		SubmodelElementCollection objects = getSMCVariable(outputVarList, "Objects");
		Property failedStage = getPropertyVariable(outputVarList, "FailedStage", DataTypeDefXsd.STRING);
		Property reason = getPropertyVariable(outputVarList, "FailureReason", DataTypeDefXsd.STRING);

		String objectName = getRequiredStringVariableValue(inputVarList, "ObjectName");
		Position entry = readPosition(inputVarList, "Entry");
		double clearance = getRequiredDoubleVariableValue(inputVarList, "Clearance");
		Preconditions.checkArgument(Double.isFinite(clearance) && clearance > 0, "Clearance must be finite and positive");
		double travelZ = getRequiredDoubleVariableValue(inputVarList, "TravelZ");
		double acceptRatio = orDefault(getDoubleVariableValue(inputVarList, "AcceptRatio"), 0.4);
		Preconditions.checkArgument(Double.isFinite(acceptRatio) && acceptRatio > 0, "AcceptRatio must be finite and positive");
		double yawDeg = orDefault(getDoubleVariableValue(inputVarList, "TargetYawDeg"), -90.0);
		Preconditions.checkArgument(Double.isFinite(yawDeg), "TargetYawDeg must be finite");
		Integer maxPasses = getIntVariableValue(inputVarList, "MaxPasses");
		Float speed = getFloatVariableValue(inputVarList, "VelocityScaling");
		double tolerance = orDefault(getDoubleVariableValue(inputVarList, "Tolerance"), 0.005);
		Preconditions.checkArgument(Double.isFinite(tolerance) && tolerance > 0, "Tolerance must be finite and positive");
		Duration settle = secondsToDuration(orDefault(getDoubleVariableValue(inputVarList, "SettleTimeout"), 15.0), "SettleTimeout");
		Duration still = secondsToDuration(orDefault(getDoubleVariableValue(inputVarList, "StillTimeout"), 10.0), "StillTimeout");
		Preconditions.checkArgument(!still.isZero(), "StillTimeout must be positive");
		Integer timeout = getIntVariableValue(inputVarList, "TimeoutSec");
		timeout = (timeout != null) ? timeout : 120;
		Preconditions.checkArgument(timeout > 0, "TimeoutSec must be positive");

		AlignHeldObject.Options options = new AlignHeldObject.Options(maxPasses != null ? maxPasses : 3,
				new MoveLinear.Options(tolerance, speed != null ? Double.valueOf(speed.toString()) : 0.15, settle),
				WaitUntilObjectStill.Options.DEFAULT.withTimeout(still), MeasureObjectOffset.Options.DEFAULT);
		AlignHeldObject operation = new AlignHeldObject(m_twin, objectName, entry, travelZ,
														Geometry.downWithYaw(Math.toRadians(yawDeg)), clearance * acceptRatio,
														options, Duration.ofSeconds(timeout));

		AlignHeldObject.Check check;
		try {
			check = runOperation(operation);
		}
		catch ( Exception e ) {
			List<StepResult<AlignHeldObject.Stage>> steps = operation.getProgress().steps();
			String where = steps.isEmpty() ? "SETTLE 진입 전 중단" : describeLast(steps.get(steps.size() - 1));
			s_logger.warn("alignment interrupted or failed: twin={}, object={}, at={}, details={}",
							m_rtConfig.getTwinId(), objectName, where, e.toString());
			throw e;
		}

		// 일부 출력만 갱신되지 않도록, 변환을 모두 끝낸 뒤에 반영한다.
		Optional<JsonNode> targetJson = check.lastTarget().map(MDTModelSerDe.MAPPER::valueToTree);
		List<SubmodelElement> sceneObjects = (objects != null) ? lastSettledObjects(check) : null;
		if ( target != null && targetJson.isPresent() ) {
			Ros2SerDes.POSE_SERDE.updateSubmodelElement(target, targetJson.get());
		}
		if ( sceneObjects != null ) {
			objects.setValue(sceneObjects);
		}
		aligned.setValue(Boolean.toString(check.aligned()));
		stopReason.setValue(check.stopReason().name());
		passes.setValue(Integer.toString(check.passes()));
		if ( errorM != null && check.errorM() != null ) {
			errorM.setValue(Double.toString(check.errorM()));
		}
		if ( failedStage != null ) {
			failedStage.setValue(check.failedStage() != null ? check.failedStage().name() : "");
		}
		if ( reason != null ) {
			reason.setValue(check.why() != null ? check.why() : "");
		}
		if ( !check.aligned() ) {
			s_logger.warn("held object not aligned: twin={}, object={}, reason={}, passes={}, errorM={}, details={}",
							m_rtConfig.getTwinId(), objectName, check.stopReason(), check.passes(), check.errorM(), check.why());
		}
	}

	/** 마지막 정착 관측의 scene 물체. 정착을 한 번도 확인하지 못했거나 변환할 수 없으면 {@code null}. */
	private List<SubmodelElement> lastSettledObjects(AlignHeldObject.Check check) {
		List<StepResult<AlignHeldObject.Stage>> steps = check.steps();
		for ( int i = steps.size() - 1; i >= 0; --i ) {
			StepResult<AlignHeldObject.Stage> step = steps.get(i);
			if ( step.objectStill() != null && step.goalReached() && step.objectStill().sample().isPresent() ) {
				return sceneObjectsOrNull(step.objectStill().sample().get().path("value").get("objects"), s_logger);
			}
		}
		return null;
	}

	private static String describeLast(StepResult<AlignHeldObject.Stage> last) {
		return last.goalReached() ? last.stage() + " 완료 후 다음 단계 진입 전 중단"
								: last.stage() + " 목표 미확인 또는 실행 중단";
	}

	/** x, y, z Property를 가진 SMC를 위치로 읽는다. 빠지거나 숫자가 아닌 성분은 거부한다. */
	private Position readPosition(Map<String, SubmodelElement> inputs, String name) {
		SubmodelElementCollection smc = getRequiredSMCVariable(inputs, name);
		double[] xyz = new double[3];
		String[] axes = { "x", "y", "z" };
		for ( int i = 0; i < axes.length; ++i ) {
			String axis = axes[i];
			SubmodelElement element = smc.getValue().stream().filter(e -> axis.equals(e.getIdShort())).findFirst().orElse(null);
			Preconditions.checkArgument(element instanceof Property, name + "." + axis + " is missing");
			try {
				xyz[i] = Double.parseDouble(((Property)element).getValue());
			}
			catch ( NullPointerException | NumberFormatException e ) {
				throw new IllegalArgumentException(name + "." + axis + " must be a number", e);
			}
			Preconditions.checkArgument(Double.isFinite(xyz[i]), name + "." + axis + " must be a finite number");
		}
		return new Position(xyz[0], xyz[1], xyz[2]);
	}

	private static double orDefault(Double value, double defaultValue) {
		return (value != null) ? value : defaultValue;
	}
}
