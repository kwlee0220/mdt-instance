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
import mdt.robot.client.operation.ResetScene;
import mdt.robot.client.operation.WaitForFreshVariable;
import mdt.task.TaskException;


/**
 * mdt-robot-client의 ResetScene으로 scene의 물체를 레시피(scene)대로 다시 배치한다.
 * 생성자는 통신하지 않으며 RobotTwinClient를 재사용한다. 동기·비동기 호출은 카탈로그를 따른다.
 * 동기 HTTP 읽기 제한은 30초, 비동기 결과 대기 상한은 120초, 폴링 간격은 1초다.
 * <p>
 * 레시피는 로봇 트윈 설정의 {@code reset_scene.backend.scenes}에 선언되어 있다. 위치를 범위 안에서
 * 무작위로 뽑는 레시피는 {@code Seed}가 같으면 같은 배치가 재현된다. 로봇 트윈은 팔과 scene을 함께
 * 점유하므로 팔이 움직이는 중에는 거부(409)된다.
 * <p>
 * 리셋이 끝난 뒤에도 {@code scene_objects} 상태 변수는 주기 발행이라 한동안 리셋 전 배치를 보고한다.
 * 적용을 확인한 뒤 WaitForFreshVariable로 OK 품질과 유효한 시각을 가진 표본의 갱신 2회를
 * 기다린다(관측 상한 5초, 조회 간격 100ms). 시각 누락·형식 오류·품질 불량은 갱신으로 인정하지 않는다.
 * 갱신 미확인은 진단을 WARN으로 남기고 정상 반환한다. 새 발행도 리셋 반영이나 물리적 정착을 보장하지 않는다.
 * 관측 상한은 진행 중인 HTTP를 즉시 중단하지 않으며, 시간 초과·인터럽트는 리셋을 되돌리지 않는다.
 * <p>
 * 원격 FAILED/CANCELLED는 OperationFailedException으로 전달한다. COMPLETED여도 배치 형식이나
 * applied_count를 확인할 수 없으면 TaskException으로 실패한다. 두 경우 모두 관측과 출력 갱신을 하지 않는다.
 * 관측 통신 오류·인터럽트도 전파하며 기존 출력을 유지한다. SceneName의 앞뒤 공백은 기존처럼 trim한다.
 * 갱신 미확인은 경고로 허용하지만, 관측 통신 오류는 기존 계약에 따라 호출자에게 전달한다.
 * 이 실패는 이미 적용된 리셋의 취소를 뜻하지 않으며 Objects도 갱신되지 않는다.
 * 도달 판정 후 부가 관절 조회의 실패를 허용하는 MoveLinearProvider와는 처리 정책이 다르다.
 *
 * <dl>
 *   <dt>입력 변수</dt>
 *   <dd>{@code SceneName} (xs:string, 필수, 레시피 이름),
 *       {@code Seed} (xs:int, 선택, 무작위 배치의 seed. 생략하면 로봇 트윈은 0을 쓴다)</dd>
 *   <dt>출력 변수</dt>
 *   <dd>{@code Objects} (SMC, 선택) — <b>명령한</b> 배치. 구조는 {@link SceneObjects} 참조.
 *       물리 시뮬레이터에서는 실제 안착 위치와 다를 수 있으므로, 실제 위치는 {@link GetSceneObjectsProvider}로 읽는다.</dd>
 * </dl>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ResetSceneProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(ResetSceneProvider.class);

	private static final WaitForFreshVariable.Options FRESH_OPTIONS =
			new WaitForFreshVariable.Options(2, Duration.ofSeconds(5), Duration.ofMillis(100));

	private final RobotTwinClient m_twin;
	private final WaitForFreshVariable.Options m_freshOptions;

	public ResetSceneProvider(ServiceContext serviceContext, Reference operationRef,
							JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		this(serviceContext, operationRef, config, FRESH_OPTIONS);
	}

	// 테스트에서는 실제 시간 상한을 짧게 설정한다. 공개 입력 계약은 그대로 유지한다.
	ResetSceneProvider(ServiceContext serviceContext, Reference operationRef, JavaOperationProviderConfig config,
			WaitForFreshVariable.Options freshOptions) throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		Preconditions.checkNotNullArgument(freshOptions, "freshOptions is null");
		m_twin = createRobotTwinClient();
		m_freshOptions = freshOptions;
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
							Map<String, SubmodelElement> inoutputVarList,
							Map<String, SubmodelElement> outputVarList) throws Exception {
		SubmodelElementCollection objectsSmc = getSMCVariable(outputVarList, "Objects");
		String scene = getRequiredStringVariableValue(inputVarList, "SceneName");
		Preconditions.checkArgument(scene != null && !scene.isBlank(), "SceneName is empty");
		Integer seed = getIntVariableValue(inputVarList, "Seed");

		ResetScene.Check check = runOperation(new ResetScene(m_twin, scene.trim(),
				seed != null ? seed.longValue() : null, m_twin.getOperationTimeout(), m_freshOptions));
		throwIfRemoteFailed(check.result(), check.why(), s_logger);
		if ( !check.applied() ) {
			s_logger.warn("scene reset application could not be confirmed: twin={}, details={}",
					m_rtConfig.getTwinId(), check.why());
			throw new TaskException("reset_scene application could not be confirmed: " + check.why());
		}

		WaitForFreshVariable.Result observation = check.observation().orElseThrow();
		if ( !observation.fresh() ) {
			s_logger.warn("scene_objects refresh could not be confirmed within {}, "
					+ "it may still report the previous placement: twin={}, details={}",
					m_freshOptions.timeout(), m_rtConfig.getTwinId(), observation.summary());
		}
		if ( objectsSmc != null ) {
			objectsSmc.setValue(SceneObjects.fromPlacementList(check.result().output("objects")));
		}
	}
}
