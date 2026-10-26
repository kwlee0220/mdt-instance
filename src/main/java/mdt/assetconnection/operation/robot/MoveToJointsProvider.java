package mdt.assetconnection.operation.robot;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

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
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.MoveToJoints;
import mdt.rt.Ros2SerDes;


/**
 * AAS의 관절 목표를 mdt-robot-client의 MoveToJoints에 전달한다.
 * Joints는 관절 이름별 목표값(rad), VelocityScaling은 선택적인 속도 배율이다.
 * 속도 생략 시 서버 기본값을 사용한다. MaxDurationSec는 서버 실행 상한이며,
 * 클라이언트 대기 상한은 기존대로 서버 상한 + 30초(생략 시 90초)다.
 * COMPLETED 결과의 FinalPose/FinalJoints는 서버 스냅샷으로 갱신하며 정착을 검증하지 않는다.
 * 도달 확인 불가만으로 실패시키지는 않는다. closed_loop이 Boolean false이면 INFO,
 * 누락·null·형식 오류이면 WARN으로 사유를 기록한다. 원격 실패·취소는 WARN으로 안내를 남긴다.
 * 원격 FAILED/CANCELLED는 MoveLinearProvider와 같이 OperationFailedException으로 전달한다.
 * 접수·통신 오류는 ExecutionException에서 원인 예외를 꺼내 전달한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MoveToJointsProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(MoveToJointsProvider.class);

	private final RobotTwinClient m_twin;

	public MoveToJointsProvider(ServiceContext serviceContext, Reference operationRef,
							JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		m_twin = createRobotTwinClient();
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
							Map<String, SubmodelElement> inoutputVarList,
							Map<String, SubmodelElement> outputVarList) throws Exception {
		SubmodelElementCollection finalPoseSmc = getRequiredSMCVariable(outputVarList, "FinalPose");
		SubmodelElementCollection finalJointsSmc = getRequiredSMCVariable(outputVarList, "FinalJoints");

		SubmodelElementCollection jointsSmc = getRequiredSMCVariable(inputVarList, "Joints");

		JsonNode jointNode = Ros2SerDes.JOINTS_SERDE.toJsonNode(jointsSmc);
		Map<String,Double> joints = new LinkedHashMap<>();
		jointNode.fields().forEachRemaining(entry -> {
			Preconditions.checkArgument(entry.getValue().isNumber(), "joint value must be numeric: " + entry.getKey());
			joints.put(entry.getKey(), entry.getValue().doubleValue());
		});
		Float vs = getFloatVariableValue(inputVarList, "VelocityScaling");
		Integer maxDuration = getIntVariableValue(inputVarList, "MaxDurationSec");
		if ( maxDuration != null ) {
			Preconditions.checkArgument(maxDuration > 0, "MaxDurationSec must be positive: " + maxDuration);
		}
		MoveToJoints.Options options = new MoveToJoints.Options(vs != null ? Double.valueOf(vs.toString()) : null,
				maxDuration != null ? Duration.ofSeconds(maxDuration) : null, getClientTimeout(maxDuration));
		MoveToJoints.Check check = runOperation(new MoveToJoints(m_twin, joints, options));
		handleMotionResult(check.result(), check.reached(), check.why(), s_logger, finalPoseSmc, finalJointsSmc);
	}
}
