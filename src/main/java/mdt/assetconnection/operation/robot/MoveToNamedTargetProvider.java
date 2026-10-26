package mdt.assetconnection.operation.robot;

import java.time.Duration;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.Preconditions;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.MoveToNamedTarget;


/**
 * SRDF에 정의된 이름 자세로 이동한다. 실행은 mdt-robot-client의 MoveToNamedTarget에 위임한다.
 * <p>
 * 속도 생략 시 서버 기본값을 사용한다. MaxDurationSec는 서버 실행 상한이며,
 * 클라이언트 대기 상한은 서버 상한 + 30초(생략 시 90초)다. 생성자는 통신하지 않는다.
 * <p>
 * FinalPose/FinalJoints는 서버 스냅샷이며 정착을 검증하지 않는다. 도달 확인 불가만으로
 * 실패시키지는 않는다. closed_loop이 Boolean false이면 INFO, 누락·null·형식 오류는 WARN이다.
 * 원격 FAILED/CANCELLED는 안내를 WARN으로 남긴 뒤 OperationFailedException으로 전달한다.
 * 접수·통신 예외는 실행 래퍼에서 원인을 꺼내 전달한다. 시간 초과 시 원격 연산을 자동 취소하지 않는다.
 * <dl>
 *   <dt>입력 변수</dt>
 *   <dd>TargetName (xs:string, 필수), VelocityScaling (xs:float, 선택),
 *       MaxDurationSec (xs:int, 선택, 양수 초)</dd>
 *   <dt>출력 변수</dt>
 *   <dd>FinalPose, FinalJoints (SMC, 필수). 서버 출력 누락·null이면 해당 기존 값을 유지한다.</dd>
 * </dl>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MoveToNamedTargetProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(MoveToNamedTargetProvider.class);

	private final RobotTwinClient m_twin;

	public MoveToNamedTargetProvider(ServiceContext serviceContext, Reference operationRef,
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

		String target = getRequiredStringVariableValue(inputVarList, "TargetName");
		Float vs = getFloatVariableValue(inputVarList, "VelocityScaling");
		Integer maxDuration = getIntVariableValue(inputVarList, "MaxDurationSec");
		if ( maxDuration != null ) {
			Preconditions.checkArgument(maxDuration > 0, "MaxDurationSec must be positive: " + maxDuration);
		}
		MoveToNamedTarget.Options options = new MoveToNamedTarget.Options(vs != null ? Double.valueOf(vs.toString()) : null,
				maxDuration != null ? Duration.ofSeconds(maxDuration) : null, getClientTimeout(maxDuration));
		MoveToNamedTarget.Check check = runOperation(new MoveToNamedTarget(m_twin, target, options));
		handleMotionResult(check.result(), check.reached(), check.why(), s_logger, finalPoseSmc, finalJointsSmc);
	}
}
