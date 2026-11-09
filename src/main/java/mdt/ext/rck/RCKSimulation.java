package mdt.ext.rck;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import utils.Preconditions;
import utils.Throwables;
import utils.func.Result;
import utils.statechart.AbstractTimedState;
import utils.statechart.Signal;
import utils.statechart.SignalResult;
import utils.statechart.Transition;
import utils.statechart.Transitions;
import utils.websocket.Signals.BinaryMessage;
import utils.websocket.Signals.ConnectionClosed;
import utils.websocket.Signals.TextMessage;
import utils.websocket.Signals.WebSocketError;
import utils.websocket.States;
import utils.websocket.States.CompletedState;
import utils.websocket.States.FailedState;
import utils.websocket.States.StoppedState;
import utils.websocket.WebSocketClosedException;
import utils.websocket.WebSocketStateChart;

import mdt.ext.rck.RCKSimulationResult.Termination;



/**
 * RCK 프레스 공정 시뮬레이터와 WebSocket으로 연동하여 시뮬레이션을 수행하는 상태 차트.
 * <p>
 * 시뮬레이터 서버에 WebSocket으로 접속한 뒤 다음 상태들을 순차적으로 거치며 시뮬레이션을 진행한다.
 * <ol>
 *   <li>{@code ConnectingWebSocket} - WebSocket 연결을 수립한다({@link States.WebSocketConnectingState}).</li>
 *   <li>{@code Registering} - 클라이언트를 등록({@code register})하고 {@code connected} 응답을 기다린다.</li>
 *   <li>{@code LayoutLoading} - 레이아웃을 요청하고 {@code facility} 메시지로 설비 정보를 수신한다.</li>
 *   <li>{@code SimulationStarting} - 시뮬레이션 시작 명령을 보내고 {@code simulation_start}를 기다린다.</li>
 *   <li>{@code Running} - {@code simulation_status}로 진행 상황을 받는다. {@code video_info}를 받으면
 *       영상 다운로드 URL을 알리고 {@code Completed}로 전이한다.</li>
 *   <li>{@code Completed} / {@code Failed} / {@code Stopped} - 종료 상태.</li>
 * </ol>
 * 응답을 기다리는 상태들은 메시지 없이 응답 대기 시간({@link Builder#responseTimeout(Duration)}, 기본 2분)이 지나면
 * {@link TimeoutException}을 원인으로 {@code Failed}로 전이한다. 메시지를 받을 때마다 대기 시간이 다시 시작된다.
 * 영상 정보를 받기 전에 시뮬레이터가 연결을 닫으면 정상 close 코드라도 {@link WebSocketClosedException}을 원인으로
 * {@code Failed}로 전이한다(사용자가 취소한 것이 아니므로).
 * <p>
 * 시뮬레이션을 멈추는 방법은 두 가지다.
 * <ul>
 *   <li>{@link #requestStop()} - 정상 절차로 멈춘다. {@code Stopping} 상태에서 시뮬레이터에 중지 명령을 보내고
 *       {@code simulation_stop} 응답(또는 시간 초과)을 받은 뒤 {@code Stopped}로 끝난다. 차트의 실행 결과는
 *       {@code COMPLETED}이고 최종 상태가 {@code /Stopped}다. 접속 전이면 바로 {@link #cancel(boolean)}한다.</li>
 *   <li>{@link #cancel(boolean)} - 즉시 취소한다. 연결되어 있으면 중지 명령을 먼저 보낸 뒤(응답은 기다리지 않음)
 *       차트를 취소하므로 실행 결과는 {@code CANCELLED}다. {@link java.util.concurrent.Future#cancel(boolean)}
 *       규약을 따른다.</li>
 * </ul>
 * <p>
 * 진행 상황(상태, 진척도, 영상 URL)은 생성자에 준 {@link SimulationObserver}에 알린다. 끝날 때는 어떤 경로로
 * 끝나든(종료 상태, 연결 오류로 인한 대체 종료, 취소) {@link SimulationObserver#onFinished}가 한 번 불린다. MDT
 * 인스턴스에 반영하려면 {@link MDTSimulationOutputReporter#newRunObserver()}가 돌려주는 관찰자를 넘긴다.
 * <p>
 * 시뮬레이션을 실행하지 않고 설비 정보만 얻으려면 {@link Builder#loadEquipmentProperties()}를 쓴다.
 * <p>
 * 다음은 시뮬레이션을 만들어 시작하고, 끝날 때까지 기다린 뒤 결과를 출력하는 예제다. 관찰자 메소드는 차트 lock을
 * 쥔 채 불리므로 짧게 끝내야 한다({@link SimulationObserver} 참조). {@link #waitForFinished()}는 실패나 취소로
 * 끝나도 예외를 던지지 않고 반환하며(인터럽트되면 {@link InterruptedException}), 종료 사유는
 * {@link #getSimulationResult()}로 확인한다.
 * <pre>{@code
 * RCKSimulation sim = RCKSimulation.builder()
 *                         .serverUrl("ws://localhost:8765/ws")		// 시뮬레이터 WebSocket URL
 *                         .clientId("mdt-client-01")				// 클라이언트 식별자
 *                         .processName("PressLine")					// 공정명
 *                         .layoutName("Layout-A")					// 레이아웃명
 *                         .responseTimeout(Duration.ofMinutes(1))	// 선택: 응답 대기 시간(기본 2분)
 *                         .observer(new SimulationObserver() {
 *                             public void onPhaseChanged(RCKSimulationPhase phase) {
 *                                 System.out.println("phase: " + phase.displayName());
 *                             }
 *                             public void onProgressUpdated(RCKSimulationProgress progress) {
 *                                 System.out.println("progress: " + progress);
 *                             }
 *                         })
 *                         .build();
 * sim.start();
 *
 * // 정상 절차로 멈추려면 다른 스레드에서 sim.requestStop()을, 즉시 취소하려면 sim.cancel(true)를 부른다.
 * sim.waitForFinished();
 *
 * RCKSimulationResult result = sim.getSimulationResult();
 * System.out.println("termination: " + result.termination().displayName());
 * System.out.println("production: " + result.progress().production());
 * if ( result.videoUrl() != null ) {
 *     System.out.println("video: " + result.videoUrl());
 * }
 * if ( result.failureCause() != null ) {
 *     result.failureCause().printStackTrace();
 * }
 * }</pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulation extends WebSocketStateChart<RCKSimulationContext> {
	private static final Logger s_logger = LoggerFactory.getLogger(RCKSimulation.class);

	private static final String STATE_CONNECTING_WEBSOCKET = "/ConnectingWebSocket";
	private static final String STATE_REGISTERING = "/Registering";
	private static final String STATE_LAYOUT_LOADING = "/LayoutLoading";
	private static final String STATE_SIMULATION_STARTING = "/SimulationStarting";
	private static final String STATE_RUNNING = "/Running";
	private static final String STATE_STOPPING = "/Stopping";
	private static final String STATE_COMPLETED = "/Completed";
	private static final String STATE_STOPPED = "/Stopped";
	private static final String STATE_FAILED = "/Failed";
	// 진행 중 상태가 관찰자에게 알리는 단계. 종료 상태는 RCKSimulationResult.Termination으로 알린다.
	private static final Map<String,RCKSimulationPhase> STATE_PHASES = Map.ofEntries(
		Map.entry(STATE_CONNECTING_WEBSOCKET, RCKSimulationPhase.CONNECTING),
		Map.entry(STATE_REGISTERING, RCKSimulationPhase.REGISTERING),
		Map.entry(STATE_LAYOUT_LOADING, RCKSimulationPhase.LOADING_LAYOUT),
		Map.entry(STATE_SIMULATION_STARTING, RCKSimulationPhase.STARTING),
		Map.entry(STATE_RUNNING, RCKSimulationPhase.RUNNING),
		Map.entry(STATE_STOPPING, RCKSimulationPhase.STOPPING)
	);

	/** 응답을 기다리는 상태들의 기본 응답 대기 시간. */
	public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofMinutes(2);
	/** {@code Stopping} 상태가 시뮬레이터의 {@code simulation_stop} 응답을 기다리는 기본 시간. */
	public static final Duration DEFAULT_STOP_RESPONSE_TIMEOUT = Duration.ofSeconds(10);
	/** 종료·취소 직전에 보내는 중지 명령의 전송 완료를 기다리는 최대 시간. */
	private static final Duration STOP_SEND_TIMEOUT = Duration.ofSeconds(2);

	private static final JsonMapper MAPPER = JsonMapper.builder()
														.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
														.build();

	private final SimulationObserver m_observer;
	private volatile Duration m_responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
	private volatile Duration m_stopResponseTimeout = DEFAULT_STOP_RESPONSE_TIMEOUT;
	// 진행 보고에서 생산량을 읽는 규칙. 빌더가 정하고, 시작한 뒤에는 바뀌지 않는다.
	private volatile RCKSimulationProgress.Format m_progressFormat = RCKSimulationProgress.Format.DEFAULT;
	// 설비 정보만 얻고 끝내는지 여부(Builder#loadEquipmentProperties). 시작한 뒤에는 바뀌지 않는다.
	private volatile boolean m_equipmentOnly = false;

	// 들어간 종료 상태의 종료 사유. 종료 알림(notifyFinished)이 결과를 만드는 데 쓴다.
	// 종료 리스너는 차트 lock 없이 불릴 수 있어 getCurrentState() 대신 이 값을 읽는다.
	private volatile Termination m_finalTermination;
	// Stopping에서 시뮬레이터가 simulation_stop으로 중지를 확인했는지 여부.
	private volatile boolean m_stopConfirmed = false;
	// 끝난 뒤의 결과. 종료 알림이 만든다.
	private volatile RCKSimulationResult m_result;

	// 송신 체인. JDK WebSocket은 텍스트 송신을 한 번에 하나만 받으므로(이전 송신이 끝나기 전의 송신은 실패한다),
	// 모든 송신을 직전 송신이 끝난 뒤에 이어서 보낸다.
	private final Object m_sendLock = new Object();
	@GuardedBy("m_sendLock") private CompletableFuture<?> m_sendTail = CompletableFuture.completedFuture(null);

	// 반복되는 경고를 줄이기 위한 기록: 처음 한 번만 WARN, 이후는 DEBUG.
	private final Set<String> m_warnedMessageTypes = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean m_warnedMalformedStatus = new AtomicBoolean();

	/**
	 * 시뮬레이션을 만드는 빌더를 반환한다. 서버 URL, 클라이언트 ID, 공정명, 레이아웃명은 반드시 지정한다.
	 *
	 * @return	새 빌더
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 진행 상황을 외부에 알리지 않는 RCK 시뮬레이션 상태 차트를 생성한다(테스트용). 일반 사용은 {@link #builder()}.
	 *
	 * @param context	시뮬레이션 서버 주소·공정명·레이아웃 등 시뮬레이션 수행에 필요한 정보를 담은 문맥
	 */
	RCKSimulation(RCKSimulationContext context) {
		this(context, SimulationObserver.NONE);
	}

	/**
	 * RCK 시뮬레이션 상태 차트를 생성한다(테스트용). 일반 사용은 {@link #builder()}. 차트를 구동하려면 생성 후
	 * {@link #start()}를 호출한다.
	 *
	 * @param context	시뮬레이션 서버 주소·공정명·레이아웃 등 시뮬레이션 수행에 필요한 정보를 담은 문맥
	 * @param observer	진행 상황을 받을 관찰자
	 */
	RCKSimulation(RCKSimulationContext context, SimulationObserver observer) {
		super(context);
		Preconditions.checkNotNullArgument(observer, "observer is null");

		m_observer = observer;
		setLogger(s_logger);

		addState(new States.WebSocketConnectingState<>(STATE_CONNECTING_WEBSOCKET, getContext(),
														STATE_REGISTERING, STATE_FAILED) {
			@Override
			public void enter() {
				// 관찰자에게 알리지 않으면 접속하는 동안 이전 실행의 상태가 그대로 보인다.
				notifyPhaseChanged(getPath());
				super.enter();
			}
		});
		addState(new Registering(STATE_REGISTERING, getContext()));
		addState(new LayoutLoading(STATE_LAYOUT_LOADING, getContext()));
		addState(new SimulationStarting(STATE_SIMULATION_STARTING, getContext()));
		addState(new Running(STATE_RUNNING, getContext()));
		addState(new Stopping(STATE_STOPPING, getContext()));
		addState(new Completed(STATE_COMPLETED, getContext()));
		addState(new Failed(STATE_FAILED, getContext()));
		addState(new Stopped(STATE_STOPPED, getContext()));

		setInitialState(STATE_CONNECTING_WEBSOCKET);
		whenFinished(this::notifyFinished);
	}

	/**
	 * 끝난 시뮬레이션의 결과를 반환한다. 종료 사유, 마지막 진행 상황, 결과 영상 URL, 실패 원인을 담는다.
	 * <p>
	 * 결과는 차트가 끝나는 순간(종료 리스너 중 가장 먼저) 만들어지므로, 나중에 등록한 종료 리스너나
	 * {@link #waitForFinished()}가 반환된 뒤에는 항상 얻을 수 있다.
	 *
	 * @return	시뮬레이션 결과
	 * @throws IllegalStateException	시뮬레이션이 아직 끝나지 않은 경우
	 */
	public RCKSimulationResult getSimulationResult() {
		RCKSimulationResult result = m_result;
		if ( result == null ) {
			throw new IllegalStateException("simulation is not finished: " + this);
		}
		return result;
	}

	/**
	 * 응답을 기다리는 상태들의 응답 대기 시간을 바꾼다(테스트용). 일반 사용은 {@link Builder#responseTimeout(Duration)}.
	 * 다음에 진입하거나 메시지를 받을 때부터 적용된다.
	 *
	 * @param timeout	응답 대기 시간 (양수)
	 */
	void setResponseTimeout(Duration timeout) {
		Preconditions.checkArgument(timeout != null && timeout.isPositive(), "invalid response timeout: %s", timeout);
		m_responseTimeout = timeout;
	}

	/**
	 * {@code Stopping}이 시뮬레이터의 {@code simulation_stop} 응답을 기다리는 시간을 바꾼다(테스트용). 일반 사용은
	 * {@link Builder#stopResponseTimeout(Duration)}.
	 *
	 * @param timeout	응답 대기 시간 (양수)
	 */
	void setStopResponseTimeout(Duration timeout) {
		Preconditions.checkArgument(timeout != null && timeout.isPositive(), "invalid stop timeout: %s", timeout);
		m_stopResponseTimeout = timeout;
	}

	/**
	 * 진행 중인 시뮬레이션을 정상 절차로 멈춘다.
	 * <p>
	 * {@link #STOP_REQUESTED} 신호로 {@code Stopping} 상태에 전이한다. {@code Stopping}은 시뮬레이터에 중지 명령을
	 * 보내고 {@code simulation_stop} 응답을 받거나 응답 대기 시간이 지나면 {@code Stopped} 종료 상태로 전이한다(차트
	 * 실행 결과는 {@code COMPLETED}). 중지 명령을 보내지 못하면 {@code Failed}로 끝난다. 실제 종료까지 기다리려면
	 * {@link #waitForFinished()}를 쓴다.
	 * <p>
	 * 시작 전이거나 아직 접속 중이어서 중지할 시뮬레이션이 없으면 차트를 취소한다. 이미 끝났으면 아무 일도 하지 않는다.
	 * <p>
	 * 접속 중처럼 STOP을 처리하는 상태가 없으면 {@link #onUnhandledSignal(Signal)}이 취소한다. 상태 콜백이나
	 * {@code whenStarted} 리스너 안에서 호출해 STOP이 큐에 들어갔다가 나중에 처리되어도 같은 경로로 취소된다.
	 * 시작 전에는 신호가 무시되므로 여기서 직접 취소한다.
	 */
	public void requestStop() {
		SignalResult<RCKSimulationContext> result = processSignal(STOP_REQUESTED);
		if ( result.status() == SignalResult.Status.IGNORED && !isDone() ) {
			// 시작 전(또는 취소 중): 신호를 처리할 차트가 실행 중이 아니다.
			cancel(true);
		}
	}

	/**
	 * 처리되지 않은 STOP을 취소로 바꾼다. 그 밖의 신호는 {@link WebSocketStateChart}의 대체 종료에 맡긴다.
	 * <p>
	 * STOP을 처리하지 않는 상태는 접속 상태({@code ConnectingWebSocket})뿐이다. 연결이 없으므로 중지 명령을 보낼
	 * 필요가 없어 이 클래스의 {@link #cancel(boolean)}을 거치지 않고 차트를 바로 취소한다. 신호 처리 중 호출되므로
	 * 취소는 종료 요청으로 접수된 뒤 확정된다.
	 *
	 * @param signal	처리되지 않은 신호
	 */
	@Override
	protected void onUnhandledSignal(Signal signal) {
		if ( signal == STOP_REQUESTED ) {
			getLogger().info("stop requested while connecting; cancelling the simulation");
			super.cancel(true);
		}
		else {
			super.onUnhandledSignal(signal);
		}
	}

	/**
	 * 시뮬레이션을 즉시 취소한다.
	 * <p>
	 * 시뮬레이터와 연결되어 있으면 중지 명령을 먼저 보내고(전송 완료까지 최대 2초, 응답은 기다리지 않음), 그 뒤
	 * 차트를 취소한다. 취소하면 연결이 닫히므로 순서를 바꾸면 명령이 전달되지 않는다. 차트의 실행 결과는
	 * {@code CANCELLED}이며 {@link java.util.concurrent.Future#cancel(boolean)} 규약을 따른다. 시뮬레이터의 응답까지
	 * 기다리는 정상 절차는 {@link #requestStop()}을 쓴다.
	 *
	 * @param mayInterruptIfRunning	{@link WebSocketStateChart#cancel(boolean)}에 전달된다.
	 * @return	취소되었으면 {@code true}. 이미 끝났으면 {@code false}.
	 */
	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		if ( isRunning() ) {
			trySendStopCommand();
		}
		return super.cancel(mayInterruptIfRunning);
	}

	/** 시뮬레이션 중지를 요청하는 내부 신호. {@link #requestStop()}이 차트에 전달한다. */
	private static final Signal STOP_REQUESTED = new Signal() {};

	/** 명령 메시지 전송에 실패했음을 알리는 내부 신호. 상태들이 받아 {@code Failed}로 전이한다. */
	private static record SendFailed(Throwable cause) implements Signal { }

	/**
	 * 시뮬레이터의 응답을 기다리는 상태들의 공통 기반 클래스.
	 * <p>
	 * 응답 대기 타이머는 {@link AbstractTimedState}가 관리한다. 진입하면 타이머를 걸고 상태 변화를 관찰자에게
	 * 알리며, 상태를 벗어나면 타이머를 취소한다. 대기 시간이 지나면 {@link #onTimeout(Duration)}이 고른 전이를
	 * 수행한다. 하위 클래스는 메시지를 받을 때 {@link #restartTimeout()}으로 대기 시간을 다시 시작한다.
	 * 타이머 신호는 {@link RCKExecutors#WORKERS}에서 보내 타이머 스레드가 전이를 실행하지 않게 한다.
	 */
	private abstract class ResponseWaitingState extends AbstractTimedState<RCKSimulationContext> {
		protected ResponseWaitingState(String path, RCKSimulationContext context) {
			super(path, context, RCKExecutors.TIMER, RCKExecutors.WORKERS);
		}

		/** 이 상태의 응답 대기 시간. {@link Builder#responseTimeout(Duration)}으로 정한 값이다. */
		@Override
		protected Duration getTimeout() {
			return m_responseTimeout;
		}

		/** 상태 변화를 관찰자에게 알린다. 재정의하면 {@code super.onEnter()}를 먼저 호출한다. */
		@Override
		protected void onEnter() {
			notifyPhaseChanged(getPath());
		}
	}

	/**
	 * JSON 텍스트 메시지의 {@code type}에 따라 전이를 고르는 상태들의 공통 기반 클래스.
	 * <p>
	 * 하위 클래스는 생성자에서 {@link #onMessageType(String, Transition)} 등으로 {@code type}별 처리기를
	 * 등록하고, 진입 시 보낼 명령이 있으면 {@link #sendRequest()}를 재정의한다. 등록되지 않은 {@code type}은
	 * 경고 로그만 남기고 머문다. 공통으로 {@link #STOP_REQUESTED}는 {@code Stopping}으로,
	 * 명령 전송 실패({@link SendFailed}), 메시지 처리 중 예외, 응답 대기 시간 초과, 시뮬레이터의
	 * 연결 종료({@link ConnectionClosed}), 연결 오류({@link WebSocketError}: pong 시간 초과 등)는
	 * {@code Failed}로 전이한다. 연결 오류를 처리하지 않으면 대체 종료가 차트를 바로 실패시켜
	 * {@code Failed} 상태의 정리(중지 명령 전송)를 거치지 않는다.
	 * 연결 종료를 처리하지 않으면 정상 close 코드일 때 {@link WebSocketStateChart}의 대체 종료가
	 * 차트를 취소하는데, 시뮬레이션 도중 끊긴 것은 사용자의 취소가 아니라 실패다.
	 */
	private abstract class JsonMessageState extends ResponseWaitingState {
		/** {@code type}별 JSON 메시지를 받아 수행할 전이를 정하는 처리기. */
		@FunctionalInterface
		private interface MessageHandler {
			/**
			 * 메시지를 처리하고 수행할 전이를 돌려준다.
			 *
			 * @param msg	수신한 메시지 전체
			 * @return	수행할 전이. {@code null}이면 안 된다.
			 * @throws Exception	처리 실패. {@code Failed} 전이가 된다.
			 */
			Transition<RCKSimulationContext> handle(JsonNode msg) throws Exception;
		}

		private final Map<String, MessageHandler> m_handlers = new HashMap<>();

		protected JsonMessageState(String path, RCKSimulationContext context) {
			super(path, context);

			on(STOP_REQUESTED, Transitions.to(STATE_STOPPING));
			on(SendFailed.class, failed -> failWith(failed.cause()));
			on(ConnectionClosed.class, closed -> {
				getLogger().warn("simulator closed the connection: code={}, reason={}, state={}",
								closed.getStatusCode(), closed.getReason(), getPath());
				return failWith(new WebSocketClosedException(closed.getStatusCode(), closed.getReason()));
			});
			on(WebSocketError.class, error -> failWith(error.getError()));
		}

		/**
		 * 진입 시 시뮬레이터에 보낼 명령을 보낸다. 기본 구현은 아무것도 보내지 않는다.
		 */
		protected void sendRequest() { }

		/**
		 * 주어진 {@code type}의 메시지를 받으면 수행할 전이를 등록한다.
		 *
		 * @param type			메시지의 {@code type} 값
		 * @param transition	수행할 전이
		 */
		protected void onMessageType(String type, Transition<RCKSimulationContext> transition) {
			onMessageType(type, msg -> transition);
		}

		/**
		 * 주어진 {@code type}의 메시지를 받으면 호출할 처리기를 등록한다. 처리기가 던진 예외는 {@code Failed} 전이가 된다.
		 *
		 * @param type		메시지의 {@code type} 값
		 * @param handler	메시지 전체를 받아 수행할 전이를 돌려주는 처리기
		 */
		protected void onMessageType(String type, MessageHandler handler) {
			m_handlers.put(type, handler);
		}

		@Override
		protected void onEnter() {
			super.onEnter();
			sendRequest();
		}

		@Override
		protected Transition<RCKSimulationContext> onTimeout(Duration timeout) {
			return failWith(new TimeoutException("no response from the simulator within " + timeout
												+ ": state=" + getPath()));
		}

		@Override
		public Optional<Transition<RCKSimulationContext>> selectTransition(Signal signal) {
			if ( signal instanceof TextMessage msg ) {
				restartTimeout();
				return Optional.of(handleMessage(msg.getText()));
			}
			return super.selectTransition(signal);
		}

		private Transition<RCKSimulationContext> handleMessage(String text) {
			try {
				JsonNode msg = MAPPER.readTree(text);
				String type = msg.path("type").asText();
				var handler = m_handlers.get(type);
				if ( handler == null ) {
					if ( m_warnedMessageTypes.add(getPath() + ":" + type) ) {
						getLogger().warn("unexpected message type: {} (state={}); further ones are logged at DEBUG",
										type, getPath());
					}
					else {
						getLogger().debug("unexpected message type: {} (state={})", type, getPath());
					}
					return Transitions.stay();
				}
				return handler.handle(msg);
			}
			catch ( Exception e ) {
				return failWith(e);
			}
		}
	}

	/**
	 * 시뮬레이터에 클라이언트를 등록하고 {@code connected} 응답을 기다리는 상태.
	 * 응답을 받으면 {@code LayoutLoading} 상태로 전이한다.
	 */
	private class Registering extends JsonMessageState {
		Registering(String path, RCKSimulationContext context) {
			super(path, context);

			onMessageType("connected", Transitions.to(STATE_LAYOUT_LOADING));
		}

		@Override
		protected void sendRequest() {
			// 상태	진입 시 시뮬레이터에게 등록 명령을 보낸다.
			getLogger().info("requesting client registration: {}", getContext().getClientId());
			sendCommand(Map.of("register", getContext().getClientId()));
		}
	}

	/**
	 * 시뮬레이터에 레이아웃 로딩을 요청하고 설비 정보를 기다리는 상태.
	 * {@code loading} 메시지가 오는 동안은 대기하고, {@code facility} 메시지로 설비 정보가 도착하면
	 * 이를 문맥에 저장한 뒤 {@code SimulationStarting} 상태로 전이한다. 설비 정보만 얻는 실행이면 시뮬레이션을
	 * 시작하지 않고 {@code Completed}로 전이한다.
	 */
	private class LayoutLoading extends JsonMessageState {
		LayoutLoading(String path, RCKSimulationContext context) {
			super(path, context);

			onMessageType("loading", Transitions.stay());
			onMessageType("facility", msg -> {
				JsonNode equipments = msg.get("message");
				getContext().setEquipmentProperties(RCKEquipmentProperties.parse(equipments));
				if ( getLogger().isInfoEnabled() ) {
					getLogger().info("received facility info: {}", equipments.toPrettyString());
				}
				return Transitions.to(m_equipmentOnly ? STATE_COMPLETED : STATE_SIMULATION_STARTING);
			});
		}

		@Override
		protected void sendRequest() {
			getLogger().info("requesting layout: {}", getContext().getLayoutName());
			sendCommand(Map.of("type", "layout",
								"target", getContext().getProcessName(),
								"name", getContext().getLayoutName()));
		}
	}

	/**
	 * 설비 정보를 담은 시작 명령을 시뮬레이터에 보내고 시뮬레이션 시작을 기다리는 상태.
	 * {@code simulation_waiting} 동안은 대기하고, {@code simulation_start}를 받으면 {@code Running} 상태로 전이한다.
	 */
	private class SimulationStarting extends JsonMessageState {
		SimulationStarting(String path, RCKSimulationContext context) {
			super(path, context);

			onMessageType("simulation_waiting", Transitions.stay());
			onMessageType("simulation_start", Transitions.to(STATE_RUNNING));
		}

		@Override
		protected void sendRequest() {
			RCKSimulationContext context = getContext();
			getLogger().info("requesting simulation start: {}", context.getProcessName());

			ObjectNode msg = MAPPER.createObjectNode();
			msg.put("type", "command");
			msg.put("target", context.getProcessName());
			// 이 상태는 facility 메시지를 받은 뒤에만 들어오므로 설비 정보는 항상 있다.
			msg.set("message", context.getEquipmentProperties().toJsonNode());
			sendCommand(msg);
		}
	}

	/**
	 * 시뮬레이션이 진행되는 동안 상태 메시지를 수신하는 상태.
	 * <p>
	 * {@code simulation_status} 메시지마다 진행 상황을 파싱하여 문맥에 저장하고 관찰자에게 알린다. 형식이 잘못된 진행
	 * 보고는 경고만 남기고 무시한다. 영상 생성 중에 오는 {@code video_progress} / {@code video_ready}는 원본 그대로
	 * 로그에만 남긴다. {@code video_info}를 받으면 영상 정보를 문맥에 저장하고, 다운로드 URL이 있으면 관찰자에게
	 * 알린 뒤 {@code Completed}로 전이한다. URL이 없어도 {@code Completed}로 끝나며 결과의 영상 URL은 {@code null}이다.
	 */
	private class Running extends JsonMessageState {
		Running(String path, RCKSimulationContext context) {
			super(path, context);

			onMessageType("simulation_status", msg -> {
				RCKSimulationProgress progress;
				try {
					progress = RCKSimulationProgress.parse(msg.get("message"), m_progressFormat);
				}
				catch ( RuntimeException e ) {
					// 진행 보고 한 건의 형식 오류로 시뮬레이션 전체를 실패시키지 않는다.
					if ( m_warnedMalformedStatus.compareAndSet(false, true) ) {
						getLogger().warn("ignored malformed simulation status: cause={}, message={}; "
										+ "further ones are logged at DEBUG", ""+e, msg);
					}
					else {
						getLogger().debug("ignored malformed simulation status: cause={}, message={}", ""+e, msg);
					}
					return Transitions.stay();
				}
				getLogger().info("received simulation progress: {}", progress);

				getContext().setLatestProgress(progress);
				notifyObserver(() -> m_observer.onProgressUpdated(progress));
				return Transitions.stay();
			});
			onMessageType("video_info", msg -> {
				String url = MAPPER.treeToValue(msg, VideoInfo.class).downloadUrl();
				getLogger().info("received video info: downloadUrl={}", url);

				getContext().setVideoUrl(url);
				if ( url != null ) {
					notifyObserver(() -> m_observer.onVideoReady(url));
				}
				return Transitions.to(STATE_COMPLETED);
			});
		}
	}

	/**
	 * 시뮬레이터가 결과 영상에 대해 보내는 {@code video_info} 메시지. 쓰는 것은 다운로드 URL뿐이며, 다른 필드는
	 * 매퍼가 무시한다.
	 */
	private record VideoInfo(String downloadUrl) { }

	/**
	 * 시뮬레이터의 중지 명령 응답을 기다리는 상태.
	 * <p>
	 * 진입하면 시뮬레이터에 중지 명령을 보낸다. 다음 중 하나가 일어나면 종료 상태로 전이한다.
	 * <ul>
	 *   <li>{@code simulation_stop} 응답 - {@code Stopped}</li>
	 *   <li>응답 대기 시간({@link Builder#stopResponseTimeout(Duration)}, 기본 10초) 초과 - 명령은 보냈으므로 경고를
	 *       남기고 {@code Stopped}</li>
	 *   <li>연결 종료({@link ConnectionClosed}) 또는 연결 오류({@link WebSocketError}) - {@code Stopped}</li>
	 *   <li>중지 명령 전송 실패({@link SendFailed}) - 시뮬레이터가 멈췄다고 볼 수 없으므로 {@code Failed}</li>
	 * </ul>
	 * 그 사이 도착하는 다른 메시지(진행 상황, 영상 등)는 무시한다.
	 */
	private class Stopping extends ResponseWaitingState {
		Stopping(String path, RCKSimulationContext context) {
			super(path, context);

			on(STOP_REQUESTED, Transitions.stay());
			on(BinaryMessage.class, Transitions.stay());
			on(ConnectionClosed.class, Transitions.to(STATE_STOPPED));
			on(WebSocketError.class, Transitions.to(STATE_STOPPED));
			on(SendFailed.class, failed -> failWith(failed.cause()));
		}

		@Override
		protected Duration getTimeout() {
			return m_stopResponseTimeout;
		}

		@Override
		protected void onEnter() {
			super.onEnter();

			sendStopCommand().whenComplete((ws, e) -> {
				if ( e != null ) {
					postSignalAsync(new SendFailed(Throwables.unwrapThrowable(e)));
				}
			});
		}

		@Override
		protected Transition<RCKSimulationContext> onTimeout(Duration timeout) {
			getLogger().warn("no simulation_stop response within {}; finishing as stopped", timeout);
			return Transitions.to(STATE_STOPPED);
		}

		@Override
		public Optional<Transition<RCKSimulationContext>> selectTransition(Signal signal) {
			if ( signal instanceof TextMessage msg ) {
				try {
					if ( MAPPER.readTree(msg.getText()).path("type").asText().equals("simulation_stop") ) {
						getLogger().info("simulator confirmed the stop");
						m_stopConfirmed = true;
						return Optional.of(Transitions.to(STATE_STOPPED));
					}
				}
				catch ( Exception e ) {
					getLogger().debug("ignored malformed message while stopping: {}", ""+e);
				}
				return Optional.of(Transitions.stay());
			}
			return super.selectTransition(signal);
		}
	}

	/**
	 * 시뮬레이션이 정상 완료된 종료 상태. 시뮬레이터에 중지 명령을 보낸 뒤 WebSocket 연결을 정상 종료한다. 연결을 닫은
	 * 뒤에는 명령을 보낼 수 없으므로 중지 명령을 먼저 보낸다. 관찰자에게는 차트가 끝날 때 {@code notifyFinished}가 알린다.
	 */
	private class Completed extends CompletedState<RCKSimulationContext> {
		Completed(String path, RCKSimulationContext context) {
			super(path, context);
		}

		@Override
		public void enter() {
			m_finalTermination = Termination.COMPLETED;
			trySendStopCommand();
			super.enter();
			getLogger().info("simulation completed");
		}
	}

	/**
	 * 시뮬레이션이 오류로 종료된 상태. 연결되어 있으면 시뮬레이터에 중지 명령을 보낸 뒤 WebSocket 연결을 종료하고,
	 * 실패 원인을 기록한다. 실패 원인은 들어오는 전이({@link Transitions#fail})가 설정한다.
	 */
	private class Failed extends FailedState<RCKSimulationContext> {
		Failed(String path, RCKSimulationContext context) {
			super(path, context);
		}

		@Override
		public void enter() {
			m_finalTermination = Termination.FAILED;
			trySendStopCommand();
			super.enter();
			getLogger().info("simulation failed: cause={}", ""+getFailureCause());
		}
	}

	/**
	 * 사용자 요청으로 시뮬레이션을 멈춘 종료 상태. {@code Stopping}에서만 도달하며 중지 명령은 이미 보냈다.
	 * WebSocket 연결을 종료한다. 차트의 실행 결과는 {@code COMPLETED}다
	 * ({@link StoppedState} 참조).
	 */
	private class Stopped extends StoppedState<RCKSimulationContext> {
		Stopped(String path, RCKSimulationContext context) {
			super(path, context);
		}

		@Override
		public void enter() {
			m_finalTermination = Termination.STOPPED;
			super.enter();
			getLogger().info("simulation stopped: confirmed={}", m_stopConfirmed);
		}
	}

	/**
	 * 주어진 원인으로 {@code Failed}에 가는 전이를 돌려준다. 원인은 전이가 {@code Failed} 상태에 설정한다.
	 */
	private Transition<RCKSimulationContext> failWith(Throwable cause) {
		return Transitions.fail(STATE_FAILED, signal -> cause);
	}

	/**
	 * 신호를 RCK 전용 스레드({@link RCKExecutors#WORKERS})에서 보낸다. 전송 future의 완료 콜백처럼 공용 스레드에서
	 * 신호를 보낼 때 쓴다. 바로 보내면 한가한 차트가 그 공용 스레드에서 전이(중지 명령 전송 대기 포함)를 실행한다.
	 */
	private void postSignalAsync(Signal signal) {
		RCKExecutors.WORKERS.execute(() -> postSignal(signal));
	}

	/**
	 * 진입한 상태의 단계를 관찰자에게 알린다.
	 *
	 * @param statePath	진입한 상태 경로 (예: {@code "/Running"})
	 */
	private void notifyPhaseChanged(String statePath) {
		RCKSimulationPhase phase = STATE_PHASES.get(statePath);
		if ( phase != null ) {
			notifyObserver(() -> m_observer.onPhaseChanged(phase));
		}
	}

	/**
	 * 차트가 끝났음을 관찰자에게 알린다. 종료 리스너로 등록되어, 종료 상태를 거치지 않고 끝난 경우(연결 오류로 인한
	 * 대체 종료, 취소)에도 한 번 불린다.
	 * <p>
	 * 시작 전 취소의 종료 리스너는 실행 결과 lock만 쥔 채 불리므로, 차트 lock을 잡는 {@code getCurrentState()} 대신
	 * {@code m_finalTermination}을 읽는다. 생성자에서 가장 먼저 등록한 종료 리스너라 다른 종료 리스너보다 먼저 불리고,
	 * 그래서 그들은 {@link #getSimulationResult()}를 쓸 수 있다.
	 */
	private void notifyFinished(Result<RCKSimulationContext> result) {
		Termination termination;
		Throwable cause = null;
		if ( result.isSuccessful() ) {
			termination = (m_finalTermination != null) ? m_finalTermination : Termination.COMPLETED;
		}
		else if ( result.isFailed() ) {
			termination = Termination.FAILED;
			cause = result.getCause();
		}
		else {
			termination = Termination.CANCELLED;
		}
		RCKSimulationContext ctx = getContext();
		RCKSimulationResult simResult = new RCKSimulationResult(termination,
												termination == Termination.STOPPED && m_stopConfirmed,
												ctx.getLatestProgress(), ctx.getVideoUrl(), cause);
		m_result = simResult;
		notifyObserver(() -> m_observer.onFinished(simResult));
	}

	/**
	 * 관찰자를 호출한다. 관찰자가 던진 예외는 경고 로그만 남기고 무시한다(상태 전이에 영향을 주지 않는다).
	 */
	private void notifyObserver(Runnable call) {
		try {
			call.run();
		}
		catch ( Exception e ) {
			getLogger().warn("simulation observer failed: {}", ""+e);
		}
	}

	/**
	 * 시뮬레이터에 현재 공정의 시뮬레이션 중지 명령을 전송한다.
	 *
	 * @return	전송이 끝나면 완료되는 future. 전송에 실패하면 예외로 완료된다.
	 */
	private CompletableFuture<WebSocket> sendStopCommand() {
		getLogger().info("requesting simulation stop: {}", getContext().getProcessName());

		return sendJson(Map.of("type", "stop", "target", getContext().getProcessName()));
	}

	/**
	 * 연결이 열려 있으면 중지 명령을 보내고 전송 완료를 잠시({@link #STOP_SEND_TIMEOUT}) 기다린다. 연결을 닫기 직전에
	 * 호출한다. 실패는 경고 로그만 남긴다(연결 정리를 막지 않는다). 설비 정보만 얻는 실행은 시뮬레이션을 시작하지
	 * 않았고, 같은 공정에서 실행 중인 다른 시뮬레이션을 멈추면 안 되므로 보내지 않는다.
	 */
	private void trySendStopCommand() {
		if ( m_equipmentOnly ) {
			return;
		}

		WebSocket ws = getWebSocket();
		// 연결이 없거나 이미 닫히는 중(상대가 close를 보냄 포함)이면 보낼 수 없다.
		if ( ws == null || ws.isOutputClosed() || ws.isInputClosed() ) {
			return;
		}
		try {
			sendStopCommand().get(STOP_SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
		catch ( Exception e ) {
			getLogger().warn("failed to send simulation stop command: {}", ""+Throwables.unwrapThrowable(e));
		}
	}

	/**
	 * 명령 메시지를 보내고, 전송이 실패하면 {@link SendFailed} 신호로 차트에 알린다(상태가 받아 {@code Failed}로 전이).
	 * 전송 결과를 확인하지 않으면 명령이 사라져도 모른 채 응답을 기다리게 되기 때문이다.
	 */
	private void sendCommand(Map<String,String> kvs) {
		failOnSendError(sendJson(kvs));
	}

	private void sendCommand(ObjectNode json) {
		failOnSendError(sendJson(json));
	}

	private void failOnSendError(CompletableFuture<WebSocket> future) {
		future.whenComplete((ws, e) -> {
			if ( e != null ) {
				getLogger().error("failed to send command: {}", ""+Throwables.unwrapThrowable(e));
				postSignalAsync(new SendFailed(Throwables.unwrapThrowable(e)));
			}
		});
	}

	/**
	 * 주어진 키-값 쌍으로 JSON 객체를 만들어 텍스트 메시지로 송신한다. 테스트에서도 쓴다.
	 *
	 * @param kvs	JSON 객체를 구성하는 키-값 쌍의 맵
	 * @return	전송이 끝나면 완료되는 future. 전송에 실패하면 예외로 완료된다.
	 * @throws RuntimeException	JSON 직렬화에 실패한 경우
	 */
	CompletableFuture<WebSocket> sendJson(Map<String,String> kvs) {
		ObjectNode msg = MAPPER.createObjectNode();
		for ( Map.Entry<String,String> kv: kvs.entrySet() ) {
			msg.put(kv.getKey(), kv.getValue());
		}
		return sendJson(msg);
	}

	/**
	 * 주어진 JSON 객체를 텍스트 메시지로 직렬화하여 WebSocket으로 송신한다.
	 * <p>
	 * JDK WebSocket은 텍스트 송신을 한 번에 하나만 받고, 이전 송신이 끝나기 전에 보낸 송신은 future가
	 * {@link IllegalStateException}으로 실패한다. 신호 처리 스레드의 명령 송신과 다른 스레드의 {@link #cancel(boolean)}이
	 * 보내는 중지 명령, 또는 시작 명령 직후의 중지 명령이 겹칠 수 있으므로, 모든 송신을 직전 송신이 끝난 뒤에 이어서
	 * 보낸다. 직전 송신이 실패해도 다음 송신은 보낸다.
	 *
	 * @param json	송신할 JSON 객체
	 * @return	전송이 끝나면 완료되는 future. 전송(또는 송신 준비)에 실패하면 예외로 완료된다.
	 * @throws RuntimeException	JSON 직렬화에 실패한 경우
	 */
	private CompletableFuture<WebSocket> sendJson(ObjectNode json) {
		String text;
		try {
			text = MAPPER.writeValueAsString(json);
		}
		catch ( JsonProcessingException e ) {
			throw new RuntimeException("failed to serialize JSON message: " + json, e);
		}

		synchronized ( m_sendLock ) {
			CompletableFuture<WebSocket> send = m_sendTail.handle((result, error) -> null)
															.thenCompose(ignored -> sendText(text));
			m_sendTail = send;
			return send;
		}
	}

	/**
	 * {@link RCKSimulation}을 만드는 빌더. 시작 전에만 바꿀 수 있는 설정(시간 제한, keep-alive)을 한 곳에서 받는다.
	 * 서버 URL, 클라이언트 ID, 공정명, 레이아웃명은 필수다.
	 */
	public static final class Builder {
		private String m_serverUrl;
		private String m_clientId;
		private String m_processName;
		private String m_layoutName;
		private SimulationObserver m_observer = SimulationObserver.NONE;
		private Duration m_responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
		private Duration m_stopResponseTimeout = DEFAULT_STOP_RESPONSE_TIMEOUT;
		private RCKSimulationProgress.Format m_progressFormat = RCKSimulationProgress.Format.DEFAULT;
		private Duration m_pingInterval;
		private Duration m_pongTimeout;

		private Builder() { }

		/** 시뮬레이터 WebSocket 서버 URL (필수). */
		public Builder serverUrl(String serverUrl) {
			m_serverUrl = serverUrl;
			return this;
		}

		/** 시뮬레이터에 등록할 클라이언트 식별자 (필수). */
		public Builder clientId(String clientId) {
			m_clientId = clientId;
			return this;
		}

		/** 시뮬레이션 대상 공정명 (필수). */
		public Builder processName(String processName) {
			m_processName = processName;
			return this;
		}

		/** 시뮬레이터에 등록된 레이아웃명 (필수). */
		public Builder layoutName(String layoutName) {
			m_layoutName = layoutName;
			return this;
		}

		/** 진행 상황을 받을 관찰자. 기본은 {@link SimulationObserver#NONE}. */
		public Builder observer(SimulationObserver observer) {
			Preconditions.checkNotNullArgument(observer, "observer is null");
			m_observer = observer;
			return this;
		}

		/** 응답을 기다리는 상태들이 메시지 없이 기다리는 최대 시간. 기본 {@link RCKSimulation#DEFAULT_RESPONSE_TIMEOUT}. */
		public Builder responseTimeout(Duration timeout) {
			Preconditions.checkArgument(timeout != null && timeout.isPositive(), "invalid response timeout: %s", timeout);
			m_responseTimeout = timeout;
			return this;
		}

		/**
		 * {@code Stopping}이 {@code simulation_stop} 응답을 기다리는 시간. 기본
		 * {@link RCKSimulation#DEFAULT_STOP_RESPONSE_TIMEOUT}.
		 */
		public Builder stopResponseTimeout(Duration timeout) {
			Preconditions.checkArgument(timeout != null && timeout.isPositive(), "invalid stop timeout: %s", timeout);
			m_stopResponseTimeout = timeout;
			return this;
		}

		/**
		 * 진행 보고({@code simulation_status})에서 생산량을 읽는 규칙. 레이아웃에 맞춰 바꾼다.
		 * 기본 {@link RCKSimulationProgress.Format#DEFAULT}.
		 */
		public Builder progressFormat(RCKSimulationProgress.Format format) {
			Preconditions.checkNotNullArgument(format, "progressFormat is null");
			m_progressFormat = format;
			return this;
		}

		/**
		 * 연결 유지 ping을 켠다. ping 주기마다 ping을 보내고, pong이 {@code pongTimeout} 안에 오지 않으면 연결 오류로
		 * 실패한다. 지정하지 않으면 ping을 보내지 않는다.
		 *
		 * @param pingInterval	ping 발송 주기
		 * @param pongTimeout	pong 응답 대기 시간
		 */
		public Builder keepAlive(Duration pingInterval, Duration pongTimeout) {
			Preconditions.checkNotNullArgument(pingInterval, "pingInterval is null");
			Preconditions.checkNotNullArgument(pongTimeout, "pongTimeout is null");
			m_pingInterval = pingInterval;
			m_pongTimeout = pongTimeout;
			return this;
		}

		/**
		 * 시뮬레이션을 만든다. 시작하려면 {@link RCKSimulation#start()}를 호출한다. 한 빌더로 여러 번 만들 수 있으며,
		 * 만들 때마다 새 시뮬레이션이다.
		 *
		 * @return	새 시뮬레이션
		 * @throws IllegalArgumentException	필수 항목이 빠졌거나 keep-alive 시간이 잘못된 경우
		 */
		public RCKSimulation build() {
			return build(m_observer);
		}

		/**
		 * 시뮬레이션을 실행하지 않고 레이아웃의 설비 정보만 얻는다. 호출한 스레드는 끝날 때까지 기다린다.
		 * <p>
		 * 접속, 등록, 레이아웃 요청을 거쳐 {@code facility} 메시지를 받으면 연결을 닫는다. 시작 명령과 중지 명령은 실패나
		 * 인터럽트로 끝날 때를 포함해 보내지 않는다. 다만 같은 공정에서 시뮬레이션이 실행 중일 때 레이아웃 요청이
		 * 시뮬레이터에 주는 영향은 확인되지 않았다.
		 * <p>
		 * {@link #observer(SimulationObserver) 관찰자}에게는 알리지 않는다. 이 실행은 시뮬레이션이 아니므로, 예를 들어
		 * MDT 출력에 시뮬레이션 완료로 반영되면 안 되기 때문이다. 전체 소요 시간에는 제한이 없고,
		 * {@link #responseTimeout(Duration) 응답 대기 시간}은 메시지를 받을 때마다 다시 시작된다.
		 *
		 * @return	설비 정보
		 * @throws IllegalArgumentException	필수 항목이 빠졌거나 keep-alive 시간이 잘못된 경우
		 * @throws ExecutionException	설비 정보를 얻지 못한 경우. 원인은 접속 실패, 응답 대기 시간 초과, 연결 종료,
		 * 								{@code facility} 메시지 형식 오류 등이다.
		 * @throws InterruptedException	기다리는 중 인터럽트된 경우. 이때 실행은 취소된다.
		 */
		public RCKEquipmentProperties loadEquipmentProperties() throws ExecutionException, InterruptedException {
			RCKSimulation sim = build(SimulationObserver.NONE);
			sim.m_equipmentOnly = true;
			sim.start();
			try {
				return sim.get().getEquipmentProperties();
			}
			catch ( InterruptedException e ) {
				sim.cancel(true);
				throw e;
			}
		}

		private RCKSimulation build(SimulationObserver observer) {
			RCKSimulationContext ctx = new RCKSimulationContext(m_serverUrl, m_clientId, m_processName, m_layoutName);
			RCKSimulation sim = new RCKSimulation(ctx, observer);
			sim.m_responseTimeout = m_responseTimeout;
			sim.m_stopResponseTimeout = m_stopResponseTimeout;
			sim.m_progressFormat = m_progressFormat;
			if ( m_pingInterval != null ) {
				sim.setPingInterval(m_pingInterval);
				sim.setPongTimeout(m_pongTimeout);
			}
			return sim;
		}
	}
}
