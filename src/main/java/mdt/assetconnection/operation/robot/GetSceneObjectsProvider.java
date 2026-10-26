package mdt.assetconnection.operation.robot;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.RuntimeInterruptedException;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.robot.client.RobotTwinClient;
import mdt.task.TaskException;


/**
 * 로봇 트윈의 {@code scene_objects} 상태 변수를 읽어 scene 안 물체들의 현재 정보를 조회한다.
 * RobotTwinClient.readRaw()로 응답 전체를 조회하며, 연산 카탈로그 조회나 세션 생성은 하지 않는다.
 * 클라이언트는 재사용하며 생성자는 통신하지 않는다. HTTP 읽기 제한은 30초다.
 * <p>
 * 물체마다 종류, 크기, 실제 위치와 자세를 돌려준다. 물체를 집기 전에 파지 좌표를 얻는 용도로 쓴다 —
 * 고정 좌표를 쓰면 물체가 그 자리에 없을 수 있다. 좌표는 로봇 base({@code panda_link0}) 기준이며
 * 물체 중심의 위치이므로, 팔 명령 좌표(예: {@code panda_link8})로 쓰려면 손끝 보정이 필요하다.
 * <p>
 * 펑션베이의 주기적 정지처럼 일시적인 STALE은 300ms 간격으로 재조회한다. 재조회 예산은
 * 최초 조회 시작부터 10초이며, NO_DATA·기타 품질·품질 누락은 즉시 {@link TaskException}으로 실패한다.
 * 진행 중인 HTTP를 예산 만료로 끊지는 않으므로 전체 호출은 10초를 넘길 수 있다.
 * 예산은 추가 요청의 시작 여부에만 적용한다. 이미 시작한 요청의 OK 응답은 예산 이후 도착해도 사용한다.
 * 남은 예산이 조회 간격 이하라 다음 요청을 시작할 수 없으면 추가 대기 없이 실패한다.
 * 재조회 후 OK로 회복하면 조회 횟수와 경과 시간을 INFO로 한 번 남긴다.
 * 이미 OK인 값의 새 발행을 추가로 기다리지는 않는다. {@link ResetSceneProvider}는 갱신을 기다리지만 시간 초과 시에도 반환하므로,
 * 리셋 직후의 조회가 항상 새 배치라는 보장은 없다.
 * HTTP 오류는 클라이언트의 TwinException 등으로, 조회 중 인식한 인터럽트는
 * RuntimeInterruptedException으로 전달하며 원인과 인터럽트 플래그를 보존한다. 재조회 간격 대기 중
 * 인터럽트도 같은 방식으로 전달한다. HTTP 오류·인터럽트는 재시도하지 않는다.
 * 품질 검사나 물체 변환에 실패하면 기존 Objects 출력은 유지한다.
 *
 * <dl>
 *   <dt>입력 변수</dt>
 *   <dd>없음</dd>
 *   <dt>출력 변수</dt>
 *   <dd>{@code Objects} (SMC, 필수) — 물체 이름을 idShort로 하는 물체 SMC들. 구조는 {@link SceneObjects} 참조.
 *       호출할 때마다 현재 scene의 물체로 구성원을 다시 만든다.</dd>
 * </dl>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class GetSceneObjectsProvider extends RobotTwinOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(GetSceneObjectsProvider.class);

	private static final Duration STALE_RETRY_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration STALE_RETRY_INTERVAL = Duration.ofMillis(300);

	private final RobotTwinClient m_twin;
	private final Duration m_retryTimeout;
	private final long m_retryBudgetNanos;
	private final long m_retryIntervalNanos;

	public GetSceneObjectsProvider(ServiceContext serviceContext, Reference operationRef,
							JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		this(serviceContext, operationRef, config, STALE_RETRY_TIMEOUT, STALE_RETRY_INTERVAL);
	}

	// 공개 설정은 유지하고, 테스트에서는 짧은 예산과 간격을 주입한다.
	GetSceneObjectsProvider(ServiceContext serviceContext, Reference operationRef, JavaOperationProviderConfig config,
			Duration retryTimeout, Duration retryInterval) throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		m_retryBudgetNanos = positiveNanos(retryTimeout, "retryTimeout");
		m_retryIntervalNanos = positiveNanos(retryInterval, "retryInterval");
		m_retryTimeout = retryTimeout;
		m_twin = createRobotTwinClient();
	}

	private static long positiveNanos(Duration value, String name) {
		if ( value == null || value.isZero() || value.isNegative() ) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		try { return value.toNanos(); }
		catch ( ArithmeticException e ) { throw new IllegalArgumentException(name + " is too large", e); }
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
							Map<String, SubmodelElement> inoutputVarList,
							Map<String, SubmodelElement> outputVarList) throws Exception {
		SubmodelElementCollection objectsSmc = getRequiredSMCVariable(outputVarList, "Objects");

		JsonNode envelope = readAvailableScene();
		objectsSmc.setValue(SceneObjects.fromSceneObjectMap(envelope.path("value").get("objects")));
	}

	private JsonNode readAvailableScene() throws TaskException {
		long started = System.nanoTime();
		int reads = 0;
		while ( true ) {
			JsonNode envelope = m_twin.readRaw("scene_objects");
			++reads;
			String quality = envelope.path("quality").asText();
			long elapsed = System.nanoTime() - started;
			if ( "OK".equals(quality) ) {
				if ( reads > 1 ) {
					s_logger.info("scene_objects quality recovered: twin={}, reads={}, elapsed_ms={}",
							m_rtConfig.getTwinId(), reads, TimeUnit.NANOSECONDS.toMillis(elapsed));
				}
				return envelope;
			}
			if ( !"STALE".equals(quality) ) {
				throw unavailable(envelope, reads, null);
			}
			long remaining = m_retryBudgetNanos - elapsed;
			if ( remaining <= 0 ) {
				throw unavailable(envelope, reads, "budget exhausted");
			}
			if ( remaining <= m_retryIntervalNanos ) {
				throw unavailable(envelope, reads, "insufficient budget for next retry");
			}
			try {
				TimeUnit.NANOSECONDS.sleep(m_retryIntervalNanos);
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				throw new RuntimeInterruptedException(e);
			}
			// 예산을 소진한 뒤에는 다음 HTTP 요청을 시작하지 않는다.
			if ( System.nanoTime() - started >= m_retryBudgetNanos ) {
				throw unavailable(envelope, reads, "budget exhausted");
			}
		}
	}

	private TaskException unavailable(JsonNode envelope, int reads, String retryFailure) {
		String quality = envelope.path("quality").asText();
		String reason = envelope.path("reason").asText("");
		return new TaskException(String.format("scene_objects is not available: quality=%s%s, reads=%d%s",
				quality, reason.isEmpty() ? "" : ", reason=" + reason, reads,
				retryFailure != null ? ", STALE retry stopped: " + retryFailure + ", budget=" + m_retryTimeout : ""));
	}
}
