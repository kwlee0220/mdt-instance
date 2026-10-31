package mdt.ext.rck;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

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
 * 시뮬레이션 진행을 MDT 인스턴스의 {@code PressSimulation} 오퍼레이션 출력 인자에 비동기로 반영한다.
 * <p>
 * 반영하는 출력 인자는 {@code State}, {@code Progress}, {@code Production}, {@code AverageUtilization}(JSON 문자열),
 * {@code SimulationVideoUrl}이다. {@code State}에는 사람이 보는 표시 이름(예: {@code 시뮬레이션 진행중})을 쓴다.
 * 프로그램이 읽는 결과 코드는 오퍼레이션의 {@code FinalState} 출력이다({@link RCKSimulationOperation} 참조).
 * <p>
 * <b>오퍼레이션 인스턴스당 하나</b>를 두고 모든 실행이 함께 쓴다. 실행마다 {@link #startRun()}으로 그 실행 전용
 * {@link SimulationObserver}를 얻는다. 기록 대상이 같은 인스턴스의 같은 출력 인자이므로, 실행마다 갱신기를 따로 두면
 * 늦게 도착한 이전 실행의 갱신(예: "시뮬레이션 완료")이 다음 실행의 갱신("소켓 연결중")을 덮어쓸 수 있다. 하나의
 * 갱신 작업이 모든 실행의 갱신을 순서대로 쓰므로 이미 보내고 있는 요청까지 포함해 순서가 보장되고, 새 실행이
 * 시작되면 이전 실행이 아직 쓰지 않은 갱신은 버린다.
 * <p>
 * 관찰자 메소드는 갱신 요청을 기록만 하고 바로 반환한다. MDT 매니저 준비와 출력 인자 준비, 실제 갱신(MDT
 * 인스턴스로의 HTTP 요청)은 모두 RCK 전용 스레드({@link RCKExecutors#WORKERS})가 한다. 그래서 생성과
 * {@link #startRun()}도 MDT에 접속하지 않고 바로 반환한다. 갱신이 밀려 있는 동안 같은 인자에 새 값이 오면 최신 값만
 * 쓴다. 출력 인자를 준비하지 못하면 경고 로그만 남기고 그 인자는 반영하지 않으며, 다음 실행에서 다시 시도한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTSimulationOutputReporter {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTSimulationOutputReporter.class);

	/** 시뮬레이션 결과가 기록되는 MDT 오퍼레이션의 이름. */
	static final String OPERATION_NAME = "PressSimulation";
	private static final String ARGUMENT_EXPR_FORMAT = "oparg:%s:" + OPERATION_NAME + ":out:%s";

	static final String ARG_STATE = "State";
	static final String ARG_PROGRESS = "Progress";
	static final String ARG_PRODUCTION = "Production";
	static final String ARG_AVERAGE_UTILIZATION = "AverageUtilization";
	static final String ARG_VIDEO_URL = "SimulationVideoUrl";
	static final List<String> ARG_NAMES = List.of(ARG_STATE, ARG_PROGRESS, ARG_PRODUCTION,
													ARG_AVERAGE_UTILIZATION, ARG_VIDEO_URL);

	/**
	 * 출력 인자 reference를 준비하는 함수. 아직 준비하지 못한 인자 이름들을 받아, 준비한 것만 담은 맵을 돌려준다.
	 * 갱신 스레드에서만 불린다.
	 */
	private final OutputReferenceResolver m_outputReferenceResolver;

	/**
	 * 출력 인자 reference를 준비하는 함수.
	 */
	@FunctionalInterface
	interface OutputReferenceResolver {
		/**
		 * 주어진 출력 인자들의 reference를 준비한다.
		 *
		 * @param argNames	준비할 출력 인자 이름들
		 * @return	준비한 인자 이름 → reference. 준비하지 못한 인자는 빠진다.
		 */
		Map<String,MDTArgumentReference> resolve(List<String> argNames);
	}

	// 아래 두 필드는 갱신 작업(drainPendingUpdates)만 읽고 쓴다. 갱신 작업은 m_lock으로 한 번에 하나만 돌므로
	// 서로 다른 스레드가 이어서 실행해도 순서가 보장된다.
	/** 준비된 출력 인자 reference. */
	private final Map<String,MDTArgumentReference> m_outputReferences = new HashMap<>();
	/** 출력 인자 준비를 마지막으로 시도한 실행 번호. 실행마다 한 번, 아직 준비하지 못한 인자만 다시 시도한다. */
	private long m_lastResolvedRun = -1;

	private record PendingUpdate(long run, String argName, PropertyValue<?> value) { }

	private final Object m_lock = new Object();
	/** 현재 실행 번호. {@link #startRun()}마다 증가한다. */
	@GuardedBy("m_lock") private long m_currentRun = 0;
	/** 아직 쓰지 않은 갱신. 인자 이름별로 최신 값만 남는다. */
	@GuardedBy("m_lock") private final Map<String,PendingUpdate> m_pendingUpdates = new LinkedHashMap<>();
	@GuardedBy("m_lock") private boolean m_writerActive = false;
	/** {@link #flushAsync()}로 기다리는 쪽. 밀린 갱신을 모두 쓰면 완료한다. */
	@GuardedBy("m_lock") private final List<CompletableFuture<Void>> m_flushWaiters = new ArrayList<>();

	/**
	 * 주어진 MDT 인스턴스의 출력 인자에 반영하는 갱신기를 만든다. MDT에 접속하지 않고 바로 반환한다.
	 *
	 * @param managerSupplier		MDT 인스턴스 매니저를 주는 함수. 갱신 스레드에서 불린다(MQTT 연결 등 시간이 걸릴 수
	 * 								있으므로). 실패하면 다음 실행에서 다시 부른다.
	 * @param instanceIdSupplier	대상 인스턴스 식별자를 주는 함수. {@code null}을 주면 반영하지 않는다.
	 */
	public MDTSimulationOutputReporter(Supplier<MDTInstanceManager> managerSupplier,
										Supplier<String> instanceIdSupplier) {
		this(argNames -> resolveArguments(managerSupplier, instanceIdSupplier, argNames));
	}

	/**
	 * 출력 인자 reference를 준비하는 함수로 생성한다(테스트용).
	 *
	 * @param resolver	출력 인자 reference를 준비하는 함수. 갱신 스레드에서 불린다.
	 */
	MDTSimulationOutputReporter(OutputReferenceResolver resolver) {
		m_outputReferenceResolver = resolver;
	}

	/**
	 * 새 실행을 시작하고 그 실행 전용 관찰자를 돌려준다.
	 * <p>
	 * 이전 실행이 아직 쓰지 않은 갱신은 버리고, 이전 실행이 남긴 진행 상황과 영상 URL을 지우는 갱신을 넣는다. 이미 보내고
	 * 있는 이전 실행의 요청이 있으면 그 뒤에 이어서 쓴다. 이전 실행의 관찰자로 이후에 들어오는 갱신은 버린다.
	 *
	 * @return	새 실행의 관찰자
	 */
	public SimulationObserver startRun() {
		long run;
		synchronized ( m_lock ) {
			run = ++m_currentRun;
			m_pendingUpdates.clear();
		}
		RunObserver observer = new RunObserver(run);
		observer.onProgressUpdated(RCKSimulationProgress.empty());
		observer.onVideoReady("");
		return observer;
	}

	/**
	 * 지금까지 들어온 갱신을 모두 쓰면 완료되는 future를 돌려준다. 밀린 갱신이 없으면 이미 완료된 future다.
	 * 갱신 실패(경고 로그만 남김)와 관계없이 쓰기를 시도한 뒤 완료된다.
	 *
	 * @return	밀린 갱신을 모두 쓰면 완료되는 future
	 */
	public CompletableFuture<Void> flushAsync() {
		synchronized ( m_lock ) {
			if ( !m_writerActive && m_pendingUpdates.isEmpty() ) {
				return CompletableFuture.completedFuture(null);
			}
			CompletableFuture<Void> waiter = new CompletableFuture<>();
			m_flushWaiters.add(waiter);
			return waiter;
		}
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
	 * 한 실행의 관찰자. 그 실행이 현재 실행일 때만 갱신을 넣는다.
	 */
	private final class RunObserver implements SimulationObserver {
		private final long m_run;

		RunObserver(long run) {
			m_run = run;
		}

		@Override
		public void onStateChanged(String statePath, String displayName) {
			enqueueOutputUpdate(m_run, ARG_STATE, PropertyValue.STRING(displayName));
		}

		@Override
		public void onProgressUpdated(RCKSimulationProgress progress) {
			enqueueOutputUpdate(m_run, ARG_PROGRESS, PropertyValue.INTEGER(progress.progress()));
			enqueueOutputUpdate(m_run, ARG_PRODUCTION, PropertyValue.INTEGER(progress.production()));
			enqueueOutputUpdate(m_run, ARG_AVERAGE_UTILIZATION,
								PropertyValue.STRING(toUtilizationJson(progress.averageUtilizations())));
		}

		@Override
		public void onVideoReady(String downloadUrl) {
			enqueueOutputUpdate(m_run, ARG_VIDEO_URL, PropertyValue.STRING(downloadUrl));
		}

		/**
		 * 최종 상태의 표시 이름을 {@code State}에 쓴다. 종료 상태를 거치지 않고 끝난 경우(연결 오류, 취소)에도 불리므로,
		 * {@code State}가 진행 중 상태에 멈추지 않는다.
		 */
		@Override
		public void onFinished(RCKSimulationResult result) {
			enqueueOutputUpdate(m_run, ARG_STATE, PropertyValue.STRING(result.termination().displayName()));
		}
	}

	/**
	 * 갱신 요청을 기록하고, 갱신 작업이 돌고 있지 않으면 시작시킨다. 바로 반환한다.
	 * 현재 실행이 아닌 실행의 갱신은 버린다.
	 */
	private void enqueueOutputUpdate(long run, String argName, PropertyValue<?> value) {
		synchronized ( m_lock ) {
			if ( run != m_currentRun ) {
				s_logger.debug("dropped an output update of a previous run: run={}, arg={}", run, argName);
				return;
			}
			m_pendingUpdates.put(argName, new PendingUpdate(run, argName, value));
			if ( m_writerActive ) {
				return;
			}
			m_writerActive = true;
		}
		RCKExecutors.WORKERS.execute(this::drainPendingUpdates);
	}

	/**
	 * 밀린 갱신을 모두 쓴다. 쓰는 동안 들어온 갱신도 이어서 쓰고, 더 없으면 기다리는 쪽({@link #flushAsync()})을
	 * 깨우고 끝낸다.
	 * <p>
	 * 예상하지 못한 {@link Error}로 중간에 빠져나가도 갱신 작업이 끝난 것으로 표시하고, 남은 갱신이 있으면 새 작업을
	 * 시작한다. 그렇지 않으면 이후의 갱신이 쌓이기만 하고 쓰이지 않는다.
	 */
	private void drainPendingUpdates() {
		boolean completed = false;
		try {
			while ( true ) {
				List<PendingUpdate> batch;
				long currentRun;
				List<CompletableFuture<Void>> waiters = null;
				synchronized ( m_lock ) {
					if ( m_pendingUpdates.isEmpty() ) {
						m_writerActive = false;
						waiters = new ArrayList<>(m_flushWaiters);
						m_flushWaiters.clear();
						completed = true;
					}
					batch = new ArrayList<>(m_pendingUpdates.values());
					m_pendingUpdates.clear();
					currentRun = m_currentRun;
				}
				if ( completed ) {
					waiters.forEach(waiter -> waiter.complete(null));
					return;
				}

				Map<String,MDTArgumentReference> refs = getOutputReferences(currentRun);
				for ( PendingUpdate update: batch ) {
					MDTArgumentReference ref = refs.get(update.argName());
					if ( ref == null ) {
						continue;
					}
					try {
						ref.updatePropertyValue(update.value());
					}
					catch ( Exception e ) {
						s_logger.warn("failed to update simulation output '{}': {}", update.argName(), ""+e);
					}
				}
			}
		}
		finally {
			if ( !completed ) {
				boolean resubmit;
				List<CompletableFuture<Void>> waiters = List.of();
				synchronized ( m_lock ) {
					resubmit = !m_pendingUpdates.isEmpty();
					m_writerActive = resubmit;
					if ( !resubmit ) {
						waiters = new ArrayList<>(m_flushWaiters);
						m_flushWaiters.clear();
					}
				}
				waiters.forEach(waiter -> waiter.complete(null));
				if ( resubmit ) {
					RCKExecutors.WORKERS.execute(this::drainPendingUpdates);
				}
			}
		}
	}

	/**
	 * 준비된 출력 인자 reference를 돌려준다. 실행마다 한 번, 아직 준비하지 못한 인자를 다시 준비해 본다.
	 */
	private Map<String,MDTArgumentReference> getOutputReferences(long run) {
		if ( m_lastResolvedRun != run && m_outputReferences.size() < ARG_NAMES.size() ) {
			m_lastResolvedRun = run;
			List<String> missing = ARG_NAMES.stream().filter(name -> !m_outputReferences.containsKey(name)).toList();
			try {
				m_outputReferences.putAll(m_outputReferenceResolver.resolve(missing));
			}
			catch ( Exception e ) {
				s_logger.warn("failed to prepare simulation outputs: {}", ""+e);
			}
		}
		return m_outputReferences;
	}

	/**
	 * MDT 인스턴스에 등록된 시뮬레이션 오퍼레이션의 출력 인자 reference들을 생성·활성화한다.
	 *
	 * @return	인자 이름 → 활성화된 reference. 준비하지 못한 인자는 경고 로그를 남기고 빠진다.
	 */
	private static Map<String,MDTArgumentReference> resolveArguments(Supplier<MDTInstanceManager> managerSupplier,
																	Supplier<String> instanceIdSupplier,
																	List<String> argNames) {
		String instId = instanceIdSupplier.get();
		if ( instId == null ) {
			s_logger.warn("unknown MDT instance id; simulation progress is not reported to MDT");
			return Map.of();
		}
		MDTInstanceManager manager = managerSupplier.get();

		Map<String,MDTArgumentReference> refs = new HashMap<>();
		for ( String argName: argNames ) {
			String argExpr = String.format(ARGUMENT_EXPR_FORMAT, instId, argName);
			try {
				ElementReference ref = ElementReferences.parseExpr(argExpr);
				if ( ref instanceof MDTArgumentReference argRef ) {
					argRef.activate(manager);
					refs.put(argName, argRef);
				}
				else {
					s_logger.warn("simulation output is not an operation argument: {}", argExpr);
				}
			}
			catch ( Exception e ) {
				s_logger.warn("failed to prepare simulation output {}: {}", argExpr, ""+e);
			}
		}
		return refs;
	}
}
