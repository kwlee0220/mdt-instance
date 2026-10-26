package mdt.assetconnection.operation.robot;

import java.io.IOException;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.GripperCheck;
import mdt.robot.client.operation.MoveGripperToNamedTarget;


/**
 * mdt-robot-client의 {@link MoveGripperToNamedTarget}으로 그리퍼를 이름으로 지정한 목표
 * ({@code open}/{@code close}/{@code grasp})로 구동한다.
 * <p>
 * 동기·비동기 호출은 서버 카탈로그를 따른다. 일반적인 동기 배포에서는 HTTP 시간 제한(읽기 30초)이,
 * 비동기 배포에서는 클라이언트의 기본 결과 대기 상한(120초)이 적용된다. 폴링 간격은 1초다.
 * 시간 초과는 원격 연산을 자동 취소하지 않는다. 생성자는 통신하지 않는다.
 * <p>
 * 원격 FAILED/CANCELLED는 안내를 WARN으로 남기고 출력 갱신 전에 OperationFailedException으로 전달한다.
 * 접수·통신 오류와 인터럽트는 실행 래퍼에서 원인을 꺼내 전달한다.
 * COMPLETED의 at_goal=false는 실패 예외로 바꾸지 않고 ReachedGoal=false로 반영한다.
 * stalled만으로 목표 달성이나 물체 파지 성공을 판단하지 않는다. 연산 출력은 다음과 같이 반영한다.
 * <ul>
 *   <li>{@code target} → {@code Pose}</li>
 *   <li>{@code width} → {@code Position}. 관절값이 아니라 개구 폭(m)이다. 값이 없으면 갱신하지 않는다.
 *   	(펑션베이 백엔드는 개구 폭을 구하지 못해 항상 NaN을 보내고, 이는 JSON에서 {@code null}이 된다)</li>
 *   <li>{@code stalled} → {@code Stalled}</li>
 *   <li>{@code at_goal} → {@code ReachedGoal}</li>
 *   <li>{@code effort} → {@code Effort} (선택). 현재 로봇 트윈은 보내지 않으므로 갱신되지 않는다.</li>
 * </ul>
 * 현재 서버는 target과 goal을 모두 반환한다. target이 없으면 goal을 사용하며,
 * 이전 버전의 출력 이름(position, reached_goal)도 Provider에서 인식한다.
 * 클라이언트 판정은 at_goal만 사용하므로, AAS 출력 변환은 호환 이름을 포함한 원격 출력을 사용한다.
 * 선택한 stalled·at_goal(reached_goal)이 Boolean이 아니면 필드 이름과 값을 WARN으로 남기고 false로 반영한다.
 * ReachedGoal=true는 그리퍼 노드의 목표 달성 보고이며, 원하는 물체의 실제 파지 성공을 보장하지 않는다.
 * 필수 출력이 누락·null이면 IOException을 던지며 기존 AAS 출력은 유지한다.
 * <dl>
 *   <dt>입력 변수</dt><dd>Target (xs:string, 필수). 목표 지원 여부는 서버가 확인한다.</dd>
 *   <dt>출력 변수</dt><dd>Pose (xs:string), Position (xs:float), Stalled·ReachedGoal (xs:boolean)은 필수,
 *       Effort (xs:float)는 선택이다.</dd>
 * </dl>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MoveGripperToTargetProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(MoveGripperToTargetProvider.class);

	private final RobotTwinClient m_twin;

	public MoveGripperToTargetProvider(ServiceContext serviceContext, Reference operationRef,
							JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		m_twin = createRobotTwinClient();
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
							Map<String, SubmodelElement> inoutputVarList,
							Map<String, SubmodelElement> outputVarList) throws Exception {
		Property poseProp = getRequiredPropertyVariable(outputVarList, "Pose", DataTypeDefXsd.STRING);
		Property posProp = getRequiredPropertyVariable(outputVarList, "Position", DataTypeDefXsd.FLOAT);
		Property effProp = getPropertyVariable(outputVarList, "Effort", DataTypeDefXsd.FLOAT);
		Property stalledProp = getRequiredPropertyVariable(outputVarList, "Stalled", DataTypeDefXsd.BOOLEAN);
		Property reachedGoalProp = getRequiredPropertyVariable(outputVarList, "ReachedGoal", DataTypeDefXsd.BOOLEAN);

		String target = getRequiredStringVariableValue(inputVarList, "Target");
		GripperCheck check = runOperation(new MoveGripperToNamedTarget(m_twin, target));
		throwIfRemoteFailed(check.result(), check.why(), s_logger);
		var outputs = check.result().outputs();

		// 일부 출력 변수만 갱신되지 않도록, 필요한 값을 모두 확인한 뒤에 반영한다.
		JsonNode targetNode = getRequiredOutput(outputs, "target", "goal");
		JsonNode width = getOutput(outputs, "width", "position");
		JsonNode stalled = getRequiredOutput(outputs, "stalled", null);
		JsonNode atGoal = getRequiredOutput(outputs, "at_goal", "reached_goal");
		boolean stalledValue = booleanOutput("stalled", stalled);
		String atGoalName = nonNull(outputs.get("at_goal")) != null ? "at_goal" : "reached_goal";
		boolean atGoalValue = booleanOutput(atGoalName, atGoal);

		// 연산 수행 결과를 Output OperationVariable들에 반영한다.
		poseProp.setValue(targetNode.asText());
		if ( width != null ) {
			posProp.setValue(String.format("%.6f", width.floatValue()));
		}
		else {
			// 성공 판정은 at_goal로 하므로, 개구 폭이 없다고 연산을 실패시키지 않는다.
			s_logger.debug("gripper width is not reported, Position is not updated: twin={}",
							m_rtConfig.getTwinId());
		}
		stalledProp.setValue(Boolean.toString(stalledValue));
		reachedGoalProp.setValue(Boolean.toString(atGoalValue));

		JsonNode effort = outputs.get("effort");
		if ( effProp != null && effort != null && effort.isNumber() ) {
			effProp.setValue(String.format("%.6f", effort.floatValue()));
		}
	}

	/** 호환 이름 선택 후 검증한다. 정상적인 Boolean false는 형식 오류가 아니다. */
	private boolean booleanOutput(String name, JsonNode value) {
		if ( !value.isBoolean() ) {
			s_logger.warn("invalid gripper output type: twin={}, operation=move_gripper_to_target, "
					+ "field={}, expected=Boolean, value={}; using false", m_rtConfig.getTwinId(), name, value);
			return false;
		}
		return value.booleanValue();
	}

	/**
	 * 연산 출력에서 주어진 이름의 값을 찾는다. 없으면 대체 이름으로 다시 찾는다.
	 *
	 * @param outputs	연산 출력.
	 * @param name		출력 이름.
	 * @param altName	대체 출력 이름 (이전 버전 로봇 트윈의 이름 등). 없으면 {@code null}.
	 * @return	출력 값.
	 * @throws IOException	두 이름 모두 값이 없는 경우.
	 */
	private static JsonNode getRequiredOutput(Map<String,JsonNode> outputs, String name, String altName)
		throws IOException {
		JsonNode value = getOutput(outputs, name, altName);
		if ( value == null ) {
			throw new IOException(String.format("gripper output '%s' is missing: outputs=%s", name, outputs.keySet()));
		}
		return value;
	}

	/**
	 * 연산 출력에서 주어진 이름의 값을 찾는다. 없으면 대체 이름으로 다시 찾는다.
	 * <p>
	 * 키는 있지만 값이 {@code null}인 경우(로봇 트윈은 NaN을 {@code null}로 보낸다)도 값이 없는 것으로 본다.
	 *
	 * @param outputs	연산 출력.
	 * @param name		출력 이름.
	 * @param altName	대체 출력 이름 (이전 버전 로봇 트윈의 이름 등). 없으면 {@code null}.
	 * @return	출력 값. 두 이름 모두 값이 없으면 {@code null}.
	 */
	private static JsonNode getOutput(Map<String,JsonNode> outputs, String name, String altName) {
		JsonNode value = outputs.get(name);
		if ( (value == null || value.isNull()) && altName != null ) {
			value = outputs.get(altName);
		}
		return ( value != null && !value.isNull() ) ? value : null;
	}
}
