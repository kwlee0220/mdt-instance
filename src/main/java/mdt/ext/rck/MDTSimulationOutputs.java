package mdt.ext.rck;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.json.JacksonUtils;

import mdt.client.HttpMDTManager;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.sm.ref.ElementReference;
import mdt.model.sm.ref.ElementReferences;
import mdt.model.sm.ref.MDTArgumentReference;
import mdt.model.sm.value.PropertyValue;

/**
 * 시뮬레이션 진행을 MDT 인스턴스의 {@code PressSimulation} 오퍼레이션 출력 인자에 반영하는 {@link SimulationObserver}.
 * <p>
 * 반영하는 출력 인자는 {@code State}, {@code Progress}, {@code Production}, {@code AverageUtilization}(JSON 문자열),
 * {@code SimulationVideoUrl}이다. 인자마다 따로 준비하고 갱신하므로, 한 인자를 준비하거나 갱신하지 못해도 경고 로그만
 * 남기고 나머지 인자는 계속 반영한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTSimulationOutputs implements SimulationObserver {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTSimulationOutputs.class);

	/** 시뮬레이션 결과가 기록되는 MDT 오퍼레이션의 이름. */
	static final String OPERATION_NAME = "PressSimulation";
	private static final String ARGUMENT_EXPR_FORMAT = "oparg:%s:" + OPERATION_NAME + ":out:%s";

	private final MDTArgumentReference m_stateRef;
	private final MDTArgumentReference m_progressRef;
	private final MDTArgumentReference m_productionRef;
	private final MDTArgumentReference m_utilizationRef;
	private final MDTArgumentReference m_videoUrlRef;

	/**
	 * 기본 설정으로 MDT 인스턴스 매니저에 접속하여 주어진 인스턴스의 출력 인자를 준비한다.
	 *
	 * @param instanceId	대상 인스턴스 식별자
	 * @return	준비된 출력 인자 묶음
	 * @throws RuntimeException	MDT 인스턴스 매니저에 접속하지 못한 경우
	 */
	public static MDTSimulationOutputs connect(String instanceId) {
		return new MDTSimulationOutputs(HttpMDTManager.connectWithDefault().getInstanceManager(), instanceId);
	}

	public MDTSimulationOutputs(MDTInstanceManager manager, String instanceId) {
		m_stateRef = findArgument(manager, instanceId, "State");
		m_progressRef = findArgument(manager, instanceId, "Progress");
		m_productionRef = findArgument(manager, instanceId, "Production");
		m_utilizationRef = findArgument(manager, instanceId, "AverageUtilization");
		m_videoUrlRef = findArgument(manager, instanceId, "SimulationVideoUrl");
	}

	/**
	 * 이전 실행이 남긴 진행 상황과 영상 URL을 지운다. 시뮬레이션을 시작하기 전에 호출한다.
	 */
	public void reset() {
		onStatus(RCKSimulationStatus.empty());
		onVideoReady("");
	}

	@Override
	public void onStateChanged(String statePath, String displayName) {
		write(m_stateRef, "State", PropertyValue.STRING(displayName));
	}

	@Override
	public void onStatus(RCKSimulationStatus status) {
		write(m_progressRef, "Progress", PropertyValue.INTEGER(status.progress()));
		write(m_productionRef, "Production", PropertyValue.INTEGER(status.production()));
		write(m_utilizationRef, "AverageUtilization",
				PropertyValue.STRING(toUtilizationJson(status.averageUtilizations())));
	}

	@Override
	public void onVideoReady(String downloadUrl) {
		write(m_videoUrlRef, "SimulationVideoUrl", PropertyValue.STRING(downloadUrl));
	}

	/**
	 * 설비 유형별 평균 이용률을 {@code AverageUtilization} 출력 인자에 기록하는 JSON 문자열로 만든다.
	 * 진행 중 반영과 {@link RCKSimulationOperation}의 최종 출력이 같은 형식을 쓰도록 한 곳에 둔다.
	 *
	 * @param utilizations	설비 유형별 평균 이용률
	 * @return	JSON 문자열
	 */
	static String toUtilizationJson(Map<String,Float> utilizations) {
		return JacksonUtils.toObjectNodeFloats(utilizations).toPrettyString();
	}

	private static void write(MDTArgumentReference ref, String argName, PropertyValue<?> value) {
		if ( ref != null ) {
			try {
				ref.updatePropertyValue(value);
			}
			catch ( Exception e ) {
				s_logger.warn("failed to update simulation output '{}': {}", argName, ""+e);
			}
		}
	}

	/**
	 * MDT 인스턴스에 등록된 시뮬레이션 오퍼레이션의 출력 인자 reference를 생성·활성화한다.
	 *
	 * @return	활성화된 인자 reference. 준비하지 못하면 경고 로그를 남기고 {@code null}.
	 */
	private static MDTArgumentReference findArgument(MDTInstanceManager manager, String instId, String argName) {
		String argExpr = String.format(ARGUMENT_EXPR_FORMAT, instId, argName);
		try {
			ElementReference ref = ElementReferences.parseExpr(argExpr);
			if ( ref instanceof MDTArgumentReference argRef ) {
				argRef.activate(manager);
				return argRef;
			}
			s_logger.warn("simulation output is not an operation argument: {}", argExpr);
		}
		catch ( Exception e ) {
			s_logger.warn("failed to prepare simulation output {}: {}", argExpr, ""+e);
		}
		return null;
	}
}
