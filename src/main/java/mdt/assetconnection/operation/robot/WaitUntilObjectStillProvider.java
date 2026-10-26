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
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.WaitUntilObjectStill;

/**
 * 클라이언트 WaitUntilObjectStill로 scene_objects 안의 한 물체가 멈출 때까지 관측하고, 그 시점의 scene 물체를
 * 돌려준다. 워크플로의 "고정 대기 + GetSceneObjects" 두 task를 관측 기반 한 task로 대신하는 용도다.
 * 로봇 명령은 보내지 않고 {@code scene_objects}만 조회한다.
 * <p>
 * 표본은 quality=OK, 시간대가 있는 received_at, 유효한 위치·단위 쿼터니언이어야 한다. 새 표본의 위치(축별)와
 * 회전이 구간 시작 자세의 허용 범위에 머물고, 로컬 경과와 received_at 경과가 모두 QuietPeriod 이상이면
 * 정착으로 본다. 정착은 관측 구간의 pose 변화에 대한 판정이며 물리적 정지, 파지·착석·쌓기 성공이나
 * 물체 끝점의 위치 오차를 보장하지 않는다. STALE은 정숙 구간을 초기화하므로, 펑션베이처럼 발행이 주기적으로
 * 멈추는 배포에서는 중단 시간(약 2초)과 재개 후 QuietPeriod, 폴링 여유를 Timeout에 포함한다.
 * <p>
 * Timeout 안에 정착하지 못한 것(TIMEOUT, NO_VALID_SAMPLE)은 실패 예외로 바꾸지 않고 Settled=false와
 * Outcome으로 정상 반환한다. 워크플로는 Settled로 다음 동작을 결정한다. Objects는 마지막 유효 응답의
 * scene 물체이며, 정착하지 못한 경우에도 갱신하지만 정착의 근거는 아니다. 유효 응답이 없으면 갱신하지 않는다.
 * 통신 오류는 원인 예외로, 인터럽트는 InterruptedException으로 전달하며 이때 출력은 반환되지 않는다.
 * 입력 검증과 물체 변환에 실패하면 출력을 갱신하지 않는다. Timeout은 진행 중인 HTTP를 즉시 끊지 않는다.
 * <dl>
 * <dt>입력</dt><dd>ObjectName (xs:string, 필수); Timeout (xs:double, 초, 기본 10, 양수);
 * PositionTolerance (xs:double, m, 기본 0.0002, 축별 위치 허용값); AngleToleranceDeg (xs:double, 도, 기본 0.5,
 * 0~180); QuietPeriod (xs:double, 초, 기본 0.6, 양수). 시간 입력은 밀리초로 반올림한다. 조회 간격은 100ms다.</dd>
 * <dt>출력</dt><dd>Settled (xs:boolean), Outcome (xs:string, SETTLED/TIMEOUT/NO_VALID_SAMPLE)은 필수;
 * Objects (SMC, 구조는 {@link SceneObjects} 참조), Diagnostics (xs:string, 관측 진단 요약)는 선택.</dd>
 * </dl>
 */
public class WaitUntilObjectStillProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(WaitUntilObjectStillProvider.class);

	private static final double DEFAULT_TIMEOUT_SECS = 10;
	private static final double DEFAULT_POSITION_TOLERANCE = 0.0002;
	private static final double DEFAULT_ANGLE_TOLERANCE_DEG = 0.5;
	private static final double DEFAULT_QUIET_SECS = 0.6;
	private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

	private final RobotTwinClient m_twin;

	public WaitUntilObjectStillProvider(ServiceContext serviceContext, Reference operationRef,
										JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		m_twin = createRobotTwinClient();
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList, Map<String, SubmodelElement> inoutputVarList,
			Map<String, SubmodelElement> outputVarList) throws Exception {
		Property settledProp = getRequiredPropertyVariable(outputVarList, "Settled", DataTypeDefXsd.BOOLEAN);
		Property outcomeProp = getRequiredPropertyVariable(outputVarList, "Outcome", DataTypeDefXsd.STRING);
		SubmodelElementCollection objectsSmc = getSMCVariable(outputVarList, "Objects");
		Property diagProp = getPropertyVariable(outputVarList, "Diagnostics", DataTypeDefXsd.STRING);

		String objectName = getRequiredStringVariableValue(inputVarList, "ObjectName");
		Duration timeout = secondsToDuration(orDefault(getDoubleVariableValue(inputVarList, "Timeout"),
															DEFAULT_TIMEOUT_SECS), "Timeout");
		Preconditions.checkArgument(!timeout.isZero(), "Timeout must be positive");
		double positionTolerance = orDefault(getDoubleVariableValue(inputVarList, "PositionTolerance"),
												DEFAULT_POSITION_TOLERANCE);
		Preconditions.checkArgument(Double.isFinite(positionTolerance) && positionTolerance >= 0,
									"PositionTolerance must be finite and nonnegative");
		double angleDeg = orDefault(getDoubleVariableValue(inputVarList, "AngleToleranceDeg"), DEFAULT_ANGLE_TOLERANCE_DEG);
		Preconditions.checkArgument(Double.isFinite(angleDeg) && angleDeg >= 0 && angleDeg <= 180,
									"AngleToleranceDeg must be in [0, 180]");
		Duration quiet = secondsToDuration(orDefault(getDoubleVariableValue(inputVarList, "QuietPeriod"),
														DEFAULT_QUIET_SECS), "QuietPeriod");
		Preconditions.checkArgument(!quiet.isZero(), "QuietPeriod must be positive");

		WaitUntilObjectStill.Options options = new WaitUntilObjectStill.Options(timeout, positionTolerance,
																				Math.toRadians(angleDeg), quiet, POLL_INTERVAL);
		WaitUntilObjectStill.Result result = runOperation(new WaitUntilObjectStill(m_twin, objectName, options));

		// 일부 출력 변수만 갱신되지 않도록, 물체 변환을 먼저 끝낸 뒤에 반영한다.
		List<SubmodelElement> objects = (objectsSmc != null && result.sample().isPresent())
				? sceneObjectsOrNull(result.sample().get().path("value").get("objects"), s_logger)
				: null;
		settledProp.setValue(Boolean.toString(result.settled()));
		outcomeProp.setValue(result.outcome().name());
		if ( objects != null ) {
			objectsSmc.setValue(objects);
		}
		if ( diagProp != null ) {
			diagProp.setValue(result.summary());
		}
		if ( !result.settled() ) {
			s_logger.warn("object did not settle: twin={}, elapsed={}, details={}",
							m_rtConfig.getTwinId(), result.elapsed(), result.summary());
		}
	}

	private static double orDefault(Double value, double defaultValue) {
		return (value != null) ? value : defaultValue;
	}
}
