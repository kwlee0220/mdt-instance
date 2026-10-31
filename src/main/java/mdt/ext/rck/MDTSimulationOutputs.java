package mdt.ext.rck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.json.JacksonUtils;

import mdt.model.instance.MDTInstanceManager;
import mdt.model.sm.ref.ElementReference;
import mdt.model.sm.ref.ElementReferences;
import mdt.model.sm.ref.MDTArgumentReference;
import mdt.model.sm.value.PropertyValue;

/**
 * 시뮬레이션 진행을 MDT 인스턴스의 {@code PressSimulation} 오퍼레이션 출력 인자에 반영하는 {@link SimulationObserver}.
 * <p>
 * 반영하는 출력 인자는 {@code State}, {@code Progress}, {@code Production}, {@code AverageUtilization}(JSON 문자열),
 * {@code SimulationVideoUrl}이다. {@code State}에는 사람이 보는 표시 이름(예: {@code 시뮬레이션 진행중})을 쓴다.
 * 프로그램이 읽는 결과 코드는 오퍼레이션의 {@code FinalState} 출력이다({@link RCKSimulationOperation} 참조).
 * 인자마다 따로 준비하고 갱신하므로, 한 인자를 준비하거나 갱신하지 못해도 경고 로그만 남기고 나머지 인자는 계속
 * 반영한다.
 * <p>
 * 관찰자 메소드는 시뮬레이션의 신호 처리 스레드에서 차트 lock을 쥔 채 불리므로, 갱신 요청을 기록만 하고 바로
 * 반환한다. 실제 갱신(MDT 인스턴스로의 HTTP 요청)은 별도 스레드가 한다. 갱신이 밀려 있는 동안 같은 인자에 새 값이
 * 오면 최신 값만 쓴다. 그래서 MDT가 느려도 시뮬레이션 메시지 처리가 밀리지 않는다.
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

	/** 갱신을 수행하는 공용 스레드 풀. 인스턴스마다 한 번에 하나의 갱신 작업만 돌린다. */
	private static final Executor WRITER_POOL = Executors.newCachedThreadPool(task -> {
		Thread thread = new Thread(task, "rck-mdt-outputs");
		thread.setDaemon(true);
		return thread;
	});

	private record PendingWrite(String argName, MDTArgumentReference ref, PropertyValue<?> value) { }

	private final Object m_lock = new Object();
	/** 아직 쓰지 않은 갱신. 인자 이름별로 최신 값만 남는다. */
	@GuardedBy("m_lock") private final Map<String,PendingWrite> m_pending = new LinkedHashMap<>();
	@GuardedBy("m_lock") private boolean m_flushing = false;

	/**
	 * 주어진 인스턴스의 출력 인자를 준비한다. 준비하지 못한 인자는 경고 로그를 남기고 반영하지 않는다.
	 *
	 * @param manager		MDT 인스턴스 매니저
	 * @param instanceId	대상 인스턴스 식별자
	 */
	public MDTSimulationOutputs(MDTInstanceManager manager, String instanceId) {
		this(findArgument(manager, instanceId, "State"),
			findArgument(manager, instanceId, "Progress"),
			findArgument(manager, instanceId, "Production"),
			findArgument(manager, instanceId, "AverageUtilization"),
			findArgument(manager, instanceId, "SimulationVideoUrl"));
	}

	/**
	 * 준비된 출력 인자 reference들로 생성한다. {@code null}인 인자는 반영하지 않는다(테스트용).
	 */
	MDTSimulationOutputs(MDTArgumentReference stateRef, MDTArgumentReference progressRef,
						MDTArgumentReference productionRef, MDTArgumentReference utilizationRef,
						MDTArgumentReference videoUrlRef) {
		m_stateRef = stateRef;
		m_progressRef = progressRef;
		m_productionRef = productionRef;
		m_utilizationRef = utilizationRef;
		m_videoUrlRef = videoUrlRef;
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
	 * 최종 상태의 표시 이름을 {@code State}에 쓴다. 종료 상태를 거치지 않고 끝난 경우(연결 오류, 취소)에도 불리므로,
	 * {@code State}가 진행 중 상태에 멈추지 않는다.
	 */
	@Override
	public void onFinished(String resultCode, String displayName, Throwable cause) {
		write(m_stateRef, "State", PropertyValue.STRING(displayName));
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

	/**
	 * 갱신 요청을 기록하고, 갱신 작업이 돌고 있지 않으면 시작시킨다. 바로 반환한다.
	 */
	private void write(MDTArgumentReference ref, String argName, PropertyValue<?> value) {
		if ( ref == null ) {
			return;
		}
		synchronized ( m_lock ) {
			m_pending.put(argName, new PendingWrite(argName, ref, value));
			if ( m_flushing ) {
				return;
			}
			m_flushing = true;
		}
		WRITER_POOL.execute(this::flush);
	}

	/**
	 * 밀린 갱신을 모두 쓴다. 쓰는 동안 들어온 갱신도 이어서 쓰고, 더 없으면 끝낸다.
	 */
	private void flush() {
		while ( true ) {
			List<PendingWrite> batch;
			synchronized ( m_lock ) {
				if ( m_pending.isEmpty() ) {
					m_flushing = false;
					return;
				}
				batch = new ArrayList<>(m_pending.values());
				m_pending.clear();
			}
			for ( PendingWrite write: batch ) {
				try {
					write.ref().updatePropertyValue(write.value());
				}
				catch ( Exception e ) {
					s_logger.warn("failed to update simulation output '{}': {}", write.argName(), ""+e);
				}
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
