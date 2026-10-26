package mdt.ext.rck;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import utils.Throwables;
import utils.func.Try;
import utils.io.IOUtils;
import utils.statechart.AbstractState;
import utils.statechart.Signal;
import utils.statechart.Transition;
import utils.statechart.Transitions;
import utils.websocket.Signals.BinaryMessage;
import utils.websocket.Signals.ConnectionClosed;
import utils.websocket.Signals.TextMessage;
import utils.websocket.States;
import utils.websocket.States.CancelledState;
import utils.websocket.States.CompletedState;
import utils.websocket.States.ErrorState;
import utils.websocket.WebSocketStateChart;

import mdt.client.HttpMDTManager;
import mdt.client.instance.HttpMDTInstanceManager;
import mdt.ext.rck.VideoInfoMessages.VideoInfo;
import mdt.ext.rck.VideoInfoMessages.VideoProgress;
import mdt.ext.rck.VideoInfoMessages.VideoReady;
import mdt.model.sm.ref.MDTArgumentReference;
import mdt.model.sm.value.PropertyValue;
import mdt.model.sm.value.PropertyValue.StringPropertyValue;


/**
 * RCK 프레스 공정 시뮬레이터와 WebSocket으로 연동하여 시뮬레이션을 수행하는 상태 차트.
 * <p>
 * 시뮬레이터 서버에 WebSocket으로 접속한 뒤 다음 상태들을 순차적으로 거치며 시뮬레이션을 진행한다.
 * <ol>
 *   <li>{@code OpenWebSocket} - WebSocket 연결을 수립한다.</li>
 *   <li>{@code Connecting} - 클라이언트를 등록({@code register})하고 {@code connected} 응답을 기다린다.</li>
 *   <li>{@code LayoutLoading} - 레이아웃을 요청하고 {@code facility} 메시지로 설비 정보를 수신한다.</li>
 *   <li>{@code SimulationStarting} - 시뮬레이션 시작 명령을 보내고 {@code simulation_start}를 기다린다.</li>
 *   <li>{@code Running} - {@code simulation_status}로 진행 상황을 받고, {@code video_info} 수신 시 영상 수신으로 전이한다.</li>
 *   <li>{@code ReceivingVideo} - 이진 메시지로 시뮬레이션 결과 영상을 수신하여 파일로 저장한다.</li>
 *   <li>{@code Completed} / {@code Failed} / {@code Cancelled} - 종료 상태.</li>
 * </ol>
 * 사용자가 {@link #cancel(boolean)}을 호출하면 {@code Stopping} 상태로 전이하여 시뮬레이션 중지 명령을 보낸다.
 * <p>
 * MDT 인스턴스 매니저에 접속이 가능한 경우({@code MDT_INSTANCE_ID} 환경변수 설정), 시뮬레이션 상태/결과/결과
 * 영상을 해당 인스턴스의 {@code PressSimulation} 오퍼레이션 출력 인자({@code State}, {@code SimulationVideo} 등)에
 * 반영한다. 접속에 실패하면 MDT 반영 없이 시뮬레이션만 수행한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulation extends WebSocketStateChart<RCKSimulationContext> {
	private static final Logger s_logger = LoggerFactory.getLogger(RCKSimulation.class);
	
	private HttpMDTInstanceManager m_manager;
	private String m_instanceId;
	private boolean m_mdtConnected = false;
	
	private static final String STATE_OPEN_WEBSOCKET = "/OpenWebSocket";
	private static final String STATE_CONNECTING = "/Connecting";
	private static final String STATE_LAYOUT_LOADING = "/LayoutLoading";
	private static final String STATE_SIMULATION_STARTING = "/SimulationStarting";
	private static final String STATE_RUNNING = "/Running";
	private static final String STATE_WAITING_VIDEO = "/WaitingVideo";
	private static final String STATE_RECEIVING_VIDEO = "/ReceivingVideo";
	private static final String STATE_COMPLETED = "/Completed";
	private static final String STATE_CANCELLED = "/Cancelled";
	private static final String STATE_FAILED = "/Failed";
	private static final Map<String,String> STATE_NAMES = Map.of(
		STATE_OPEN_WEBSOCKET, "소켓 개발",
		STATE_CONNECTING, "시뮬에이터 연결중",
		STATE_LAYOUT_LOADING, "레이아웃 로딩중",
		STATE_SIMULATION_STARTING, "시뮬레이션 시작중",
		STATE_RUNNING, "시뮬레이션 진행중",
		STATE_WAITING_VIDEO, "영상 생성중",
		STATE_RECEIVING_VIDEO, "영상 수신중",
		STATE_COMPLETED, "시뮬레이션 완료",
		STATE_CANCELLED, "시뮬레이션 취소됨",
		STATE_FAILED, "시뮬레이션 실패"
	);

	private static final String MSG_TYPE_VIDEO_PROGRESS = "video_progress";
	private static final String MSG_TYPE_VIDEO_READY = "video_ready";
	private static final String MSG_TYPE_VIDEO_INFO = "video_info";
	
	private SimulationOutputUpdater m_utilizationUpdater;
	private MDTArgumentReference m_stateRef;
	private MDTArgumentReference m_videoRef;

	private static final long PSEUDO_CHUNK_SIZE = 1024 * 1024;	// 1MB
	private long m_pseudoChunkRemains;

	/**
	 * 주어진 시뮬레이션 문맥으로 RCK 시뮬레이션 상태 차트를 생성한다.
	 * <p>
	 * 모든 상태를 등록하고 초기/종료 상태를 설정한 뒤, MDT 인스턴스 매니저 접속을 시도한다.
	 * MDT 접속에 실패하더라도 예외를 던지지 않고 MDT 반영 없이 동작하도록 구성된다.
	 * 차트를 실제로 구동하려면 생성 후 {@link #start()}를 호출해야 한다.
	 *
	 * @param context	시뮬레이션 서버 주소·공정명·레이아웃 등 시뮬레이션 수행에 필요한 정보를 담은 문맥
	 */
	public RCKSimulation(RCKSimulationContext context) {
		super(context);
		setLogger(s_logger);
		
		// 상태 등록
		addState(new States.OpenWebSocket<>(STATE_OPEN_WEBSOCKET, getContext(), this,
											STATE_CONNECTING, STATE_FAILED));
		addState(new Connecting(STATE_CONNECTING, getContext()));
		addState(new LayoutLoading(STATE_LAYOUT_LOADING, getContext()));
		addState(new SimulationStarting(STATE_SIMULATION_STARTING, getContext()));
		addState(new Running(STATE_RUNNING, getContext()));
		addState(new WaitingVideo(STATE_WAITING_VIDEO, getContext()));
		addState(new ReceivingVideo(STATE_RECEIVING_VIDEO, getContext()));
		addState(new Completed(STATE_COMPLETED, getContext()));
		addState(new Failed(STATE_FAILED, getContext()));
		addState(new Cancelled(STATE_CANCELLED, getContext()));
		
		setInitialState(STATE_OPEN_WEBSOCKET);
		addFinalState(STATE_COMPLETED);
		addFinalState(STATE_FAILED);
		addFinalState(STATE_CANCELLED);
		
		connectMdtInstanceManager();
	}
	
	/**
	 * 진행 중인 시뮬레이션의 중지를 요청한다.
	 * <p>
	 * {@link #STOP} 신호를 차트에 전달하여 현재 상태에서 {@code Stopping} 상태로 전이시킨다.
	 * {@code Stopping} 상태는 시뮬레이터에 중지 명령을 보내고 {@code simulation_stop} 응답을 받으면
	 * {@code Cancelled} 종료 상태로 전이한다. 실제 종료까지 대기하려면 {@link #waitForFinished()}를 사용한다.
	 *
	 * @param mayInterruptIfRunning	(본 구현에서는 사용되지 않음)
	 * @return	항상 {@code true}
	 */
	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		handleSignal(STOP);
		return true;
	}

	/** 시뮬레이션 중지를 요청하는 내부 신호. {@link #cancel(boolean)} 호출 시 차트에 전달된다. */
	private static final Signal STOP = new Signal() {};
	
	private static final JsonMapper MAPPER = JsonMapper.builder()
														.addModule(new JavaTimeModule())
														.findAndAddModules()
														.build();

	/**
	 * 텍스트(JSON) 메시지를 처리하는 상태들의 공통 기반 클래스.
	 * <p>
	 * {@link utils.websocket.Signals.TextMessage} 수신 시 메시지를 JSON으로 파싱하여
	 * {@link #handleMessage(JsonNode)}에 위임하고, {@link #STOP} 신호 수신 시 {@code Stopping} 상태로 전이한다.
	 * JSON 파싱 또는 메시지 처리 중 예외가 발생하면 실패 원인을 문맥에 기록하고 {@code Failed} 상태로 전이한다.
	 */
	private abstract class AbstractTextHandlingState extends AbstractState<RCKSimulationContext> {
		/**
		 * 수신된 JSON 메시지를 처리하여 다음 전이를 결정한다.
		 *
		 * @param msg	수신된 JSON 메시지
		 * @return	수행할 전이. 처리할 전이가 없으면(메시지를 무시하는 경우) {@code null}을 반환할 수 있다.
		 * @throws Exception	메시지 처리 중 오류가 발생한 경우 (호출자가 {@code Failed} 상태로 전이시킨다)
		 */
		abstract protected Transition<RCKSimulationContext> handleMessage(JsonNode msg) throws Exception;

		protected AbstractTextHandlingState(String name, RCKSimulationContext context) {
			super(name, context);
		}

		@Override
		public Optional<Transition<RCKSimulationContext>> selectTransition(Signal signal) {
			if ( signal instanceof TextMessage msg ) {
				try {
					JsonNode jnode = MAPPER.readTree(msg.getMessage());
					return Optional.ofNullable(handleMessage(jnode));
				}
				catch ( Exception e ) {
					getContext().setFailureCause(e);
					return Optional.of(Transitions.noop(STATE_FAILED));
				}
			}
			else if ( signal == STOP ) {
				return Optional.of(Transitions.noop(STATE_CANCELLED));
			}
			else {
				return Optional.empty();
			}
		}
	}
	
	/**
	 * 시뮬레이터에 클라이언트를 등록하고 {@code connected} 응답을 기다리는 상태.
	 * 응답을 받으면 {@code LayoutLoading} 상태로 전이한다.
	 */
	private class Connecting extends AbstractTextHandlingState {
		protected Connecting(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			// 시뮬레이터에 클라이언트 등록을 요청한다.
			// 성공적으로 등록되면 시뮬레이터로부터 {@code connected} 메시지가 도착할 것이다.
			getLogger().info("requesting client registration: {}", getContext().getClientId());
			requestRegister();
			
			updateSimulationState(getPath());
		}
		
		@Override
		protected Transition<RCKSimulationContext> handleMessage(JsonNode payload) throws Exception {
			String type = payload.get("type").asText();
			if ( type.equals("connected") ) {
				return Transitions.noop(STATE_LAYOUT_LOADING);
			}
			else {
				String errorMsg = String.format("Unexpected message type: %s (state=%s)", type, this);
				s_logger.warn(errorMsg);
				
				return null;
			}
		}
		
		private void requestRegister() {
			sendJson(Map.of("register", getContext().getClientId()));
		}
	}
	
	/**
	 * 시뮬레이터에 레이아웃 로딩을 요청하고 설비 정보를 기다리는 상태.
	 * {@code loading} 메시지가 오는 동안은 대기하고, {@code facility} 메시지로 설비 정보가 도착하면
	 * 이를 문맥에 저장한 뒤 {@code SimulationStarting} 상태로 전이한다.
	 */
	private class LayoutLoading extends AbstractTextHandlingState {
		protected LayoutLoading(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			// 시뮬레이터에 레이아웃 로딩을 요청한다.
			getLogger().info("requesting layout: {}", getContext().getLayoutName());
			requestLayout();
			
			// MDT 인스턴스의 State 출력 인자를 현재 상태로 갱신한다.
			updateSimulationState(getPath());
		}
	
		@Override
		protected Transition<RCKSimulationContext> handleMessage(JsonNode payload) throws Exception {
			String type = payload.get("type").asText();
	
			// "loading" 메시지가 도착하면 레이아웃 로딩이 진행 중이라는 의미이므로 대기한다.
			if ( type.equals("loading") ) {
				return Transitions.stay();
			}
			// "facility"가 도착하면 layout 정보가 도착한 것이기 때문에
			// layout 정보를 context에 설정하고 시뮬레이션을 시작시킨다.
			else if ( type.equals("facility") ) {
				JsonNode equipProps = payload.get("message");
				getContext().setEquipmentProperties(equipProps);
				if ( s_logger.isInfoEnabled() ) {
					s_logger.info("received facility info: {}", equipProps.toPrettyString());
				}
				
				return Transitions.noop(STATE_SIMULATION_STARTING);
			}
			else {
				String errorMsg = String.format("Unexpected message type: %s (state=%s)", type, this);
				s_logger.warn(errorMsg);
				
				return null;
			}
		}
		
		private void requestLayout() {
			sendJson(Map.of("type", "layout",
							"target", getContext().getProcessName(),
							"name", getContext().getLayoutName()));
		}
	}
	
	/**
	 * 설비 정보를 담은 시작 명령을 시뮬레이터에 보내고 시뮬레이션 시작을 기다리는 상태.
	 * {@code simulation_waiting} 동안은 대기하고, {@code simulation_start}를 받으면 {@code Running} 상태로 전이한다.
	 */
	private class SimulationStarting extends AbstractTextHandlingState {
		protected SimulationStarting(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			// 시뮬레이터에 시뮬레이션 시작 명령을 전송한다.
			getLogger().info("requesting simulation start: {}", getContext().getProcessName());
			startSimulation();
			
			// MDT 인스턴스의 State 출력 인자를 현재 상태로 갱신한다.
			updateSimulationState(getPath());
		}
	
		@Override
		protected Transition<RCKSimulationContext> handleMessage(JsonNode payload) throws Exception {
			String type = payload.get("type").asText();
			if ( type.equals("simulation_waiting") ) {
				// 시뮬레이션이 시작되기 전까지는 대기한다.
				return Transitions.stay();
			}
			else if ( type.equals("simulation_start") ) {
				// 시뮬레이션이 시작되었으므로 Running 상태로 전이한다.
				return Transitions.noop(STATE_RUNNING);
			}
			else {
				String errorMsg = String.format("Unexpected message type: %s (state=%s)", type, this);
				s_logger.warn(errorMsg);
				
				return null;
			}
		}
		
		private void startSimulation() {
			RCKSimulationContext context = getContext();
			JsonNode props = context.getEquipmentProperties();

			ObjectNode msg = MAPPER.createObjectNode();
			msg.put("type", "command");
			msg.put("target", context.getProcessName());
			msg.set("message", props != null ? props : MAPPER.nullNode());
			sendJson(msg);
		}
	}
	
	/**
	 * 시뮬레이션이 진행되는 동안 상태 메시지를 수신하는 상태.
	 * {@code simulation_status} 메시지마다 결과를 파싱하여 (MDT 접속 시) 출력 인자에 반영하고 문맥에 저장하며,
	 * {@code video_info} 메시지를 받으면 결과 영상 정보를 문맥에 저장하고 {@code ReceivingVideo} 상태로 전이한다.
	 */
	private class Running extends AbstractTextHandlingState {
		protected Running(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			updateSimulationState(getPath());
		}
	
		@Override
		protected Transition<RCKSimulationContext> handleMessage(JsonNode payload) throws Exception {
			String type = payload.get("type").asText();
			if ( type.equals("simulation_status") ) {
				JsonNode message = payload.get("message");
				RCKSimulationResult result = RCKSimulationResult.parse(message);
				getLogger().info("{}: received simulation status: {}", this, result);
				
				// MDT 인스턴스 매니저에 접속되어 있는 경우, 시뮬레이션 결과 (진행율, 제품 생산 갯수)를
				// 출력 인자에 반영한다. 접속되어 있지 않으면 결과를 문맥에만 저장한다.
				if ( m_mdtConnected ) {
					try {
						m_utilizationUpdater.update(result);
					}
					catch ( Exception e ) {
						getLogger().warn("failed to update simulation status", e);
					}
				}
				getContext().setSimulationResult(result);
				
				return Transitions.stay();
			}
			else if ( type.equals("video_info") ) {
				// 시뮬레이션 결과 영상 정보를 문맥에 저장하고 ReceivingVideo 상태로 전이해서,
				// 이후 도착하는 영상 이진 데이터를 파일로 기록한다.
				VideoInfo video = MAPPER.treeToValue(payload, VideoInfo.class);
				getLogger().info("{}: received simulation status: {}", this, video);
				
				return Transitions.noop(STATE_WAITING_VIDEO);
			}
			else {
				String errorMsg = String.format("Unexpected message type: %s (state=%s)", type, this);
				s_logger.warn(errorMsg);
				
				return null;
			}
		}
	}

	/**
	 * 시뮬레이션 결과 영상을 이진 메시지로 수신하여 파일에 저장하는 상태.
	 * <p>
	 * 진입 시 {@code video_info}로 받은 파일명·크기로 출력 파일을 열고, 이진 메시지가 도착할 때마다 파일에 기록하며
	 * 남은 크기({@code m_remains})를 차감한다. 남은 크기가 0 이하가 되면 {@code Completed} 상태로 전이한다.
	 * 수신 도중 {@link #STOP} 신호를 받으면 {@code Stopping}으로, 연결이 끊기면({@link ConnectionClosed})
	 * 시뮬레이션 자체는 종료된 것으로 보아 {@code Completed}로 전이한다.
	 * <p>
	 * 상태를 벗어날 때({@link #exit()}) 수신이 완료되었으면(MDT 영상 reference가 있을 경우) 영상 파일을 출력 인자에
	 * 첨부한다. 미완료 상태로 중단되었으면 부분 수신된 파일을 삭제하고, 문맥의 영상 정보
	 * ({@link RCKSimulationContext#getSimulationVideo()})도 {@code null}로 되돌려 결과를 수집하는 측이
	 * 영상이 없음을 알 수 있게 한다.
	 * <p>
	 * 영상 수신이 중단되더라도 시뮬레이션 자체는 이미 끝난 상태이므로 진척도·생산량 등 나머지 결과는 유효하다.
	 * 따라서 이 경우도 {@code Failed}가 아니라 {@code Completed}로 종료하되 영상만 없는 것으로 처리한다.
	 */
	private final class WaitingVideo extends AbstractState<RCKSimulationContext> {
		protected WaitingVideo(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			updateSimulationState(getPath());
		}
		
		@Override
		public void exit() {
			// 수신된 비디오가 저장될 MDT reference가 존재하는 경우,
			// 해당 reference 장소에 비디오 파일을 복사한다.
			if ( m_videoRef != null ) {
				try {
					String videoUrl = getContext().getSimulationVideoInfo().downloadUrl();
					m_videoRef.updateValue(PropertyValue.STRING(videoUrl));
					s_logger.info("update SimulationVideoUrl: {}", videoUrl);
				}
				catch ( Exception e ) {
					s_logger.error("Failed to update SimulationVideo output: cause=" + e, e);
				}
			}
		}
	
		@Override
		public Optional<Transition<RCKSimulationContext>> selectTransition(Signal signal) {
			if ( signal instanceof TextMessage msg ) {
				String payload = msg.getMessage();
				try {
					JsonNode jnode = MAPPER.readTree(payload);
					String type = jnode.get("type").asText();
					switch ( type ) {
						case MSG_TYPE_VIDEO_PROGRESS:
							VideoProgress progress = MAPPER.treeToValue(jnode, VideoProgress.class);
							getLogger().debug("{}: received video progress: {}", this, progress);
							return Optional.of(Transitions.stay());
						case MSG_TYPE_VIDEO_READY:
							VideoReady ready = MAPPER.treeToValue(jnode, VideoReady.class);
							getLogger().debug("{}: received: {}", this, ready);
							return Optional.of(Transitions.stay());
						case MSG_TYPE_VIDEO_INFO:
							VideoInfo videInfo = MAPPER.treeToValue(jnode, VideoInfo.class);
							getContext().setSimulationVideoInfo(videInfo);
							getLogger().info("{}: received: {}", this, videInfo);
							
							return Optional.of(Transitions.noop(STATE_RECEIVING_VIDEO));
						default:
							System.out.println("Unexpected message type: " + type + " (state=" + this + ")");
							return Optional.of(Transitions.stay());
					}
				}
				catch ( Exception e ) {
					getContext().setFailureCause(e);
					return Optional.of(Transitions.noop(STATE_FAILED));
				}
			}
			else if ( signal == STOP ) {
				return Optional.of(Transitions.noop(STATE_CANCELLED));
			}
			else if ( signal instanceof ConnectionClosed ) {
				// 비디오 수신 중 연결이 끊어지더라고 시뮬레이션 자체는 종료된 상태이기 때문에
				// 완료 상태로 전이한다.
				return Optional.of(Transitions.noop(STATE_COMPLETED));
			}
			else {
				return Optional.empty();
			}
		}
	}


	/**
	 * 시뮬레이션 결과 영상을 이진 메시지로 수신하여 파일에 저장하는 상태.
	 * <p>
	 * 진입 시 {@code video_info}로 받은 파일명·크기로 출력 파일을 열고, 이진 메시지가 도착할 때마다 파일에 기록하며
	 * 남은 크기({@code m_remains})를 차감한다. 남은 크기가 0 이하가 되면 {@code Completed} 상태로 전이한다.
	 * 수신 도중 {@link #STOP} 신호를 받으면 {@code Stopping}으로, 연결이 끊기면({@link ConnectionClosed})
	 * 시뮬레이션 자체는 종료된 것으로 보아 {@code Completed}로 전이한다.
	 * <p>
	 * 상태를 벗어날 때({@link #exit()}) 수신이 완료되었으면(MDT 영상 reference가 있을 경우) 영상 파일을 출력 인자에
	 * 첨부한다. 미완료 상태로 중단되었으면 부분 수신된 파일을 삭제하고, 문맥의 영상 정보
	 * ({@link RCKSimulationContext#getSimulationVideo()})도 {@code null}로 되돌려 결과를 수집하는 측이
	 * 영상이 없음을 알 수 있게 한다.
	 * <p>
	 * 영상 수신이 중단되더라도 시뮬레이션 자체는 이미 끝난 상태이므로 진척도·생산량 등 나머지 결과는 유효하다.
	 * 따라서 이 경우도 {@code Failed}가 아니라 {@code Completed}로 종료하되 영상만 없는 것으로 처리한다.
	 */
	private final class ReceivingVideo extends AbstractState<RCKSimulationContext> {
		private File m_videoFile;
		private FileOutputStream m_fos;

		// 남은 비디오 크기.
		// 남은 값이 0이 되면 비디오 수신이 완료된 것으로 간주하고 상태를 벗어난다.
		private long m_remains;
		
		protected ReceivingVideo(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			try {
				// 수신된 비디오 정보를 저장할 파일을 생성하고 수신 준비한다.
				// 파일명은 시뮬레이터가 보낸 값이기 때문에 절대 경로나 상위 디렉토리 참조('..')가
				// 포함되더라도 작업 디렉토리를 벗어나지 않도록 이름 부분만 취한다.
				VideoInfo videoInfo = getContext().getSimulationVideoInfo();
				m_videoFile = new File(new File(videoInfo.fileName()).getName());
				m_fos = new FileOutputStream(m_videoFile, false);
				m_remains = videoInfo.fileSize();
				m_pseudoChunkRemains = (m_remains / PSEUDO_CHUNK_SIZE) + 1;
				updateSimulationState(getPath());
			}
			catch ( FileNotFoundException e ) {
				throw new RuntimeException("failed to open output file: " +  m_videoFile, e);
			}
		}
		
		@Override
		public void exit() {
			IOUtils.closeQuietly(m_fos);
			
			if ( m_remains <= 0 ) {
				// 수신된 비디오가 저장될 MDT reference가 존재하는 경우,
				// 해당 reference 장소에 비디오 파일을 복사한다.
				if ( m_videoRef != null ) {
					try {
						m_videoRef.updateAttachment(m_videoFile);
						s_logger.info("update Simulation Output SimulationVideo");
					}
					catch ( Exception e ) {
						s_logger.error("Failed to update SimulationVideo output: cause=" + e, e);
					}
				}
			}
			else {
				s_logger.warn("video receiving interrupted: remains={}", m_remains);
				m_videoFile.delete();

				// 온전하지 않은 영상은 결과로 사용할 수 없기 때문에 파일과 함께 문맥에서도 제거한다.
				// 이렇게 해야 시뮬레이션 결과를 수집하는 측이 '영상 없음'을 인지할 수 있다.
				getContext().setSimulationVideoInfo(null);
			}
		}
	
		@Override
		public Optional<Transition<RCKSimulationContext>> selectTransition(Signal signal) {
			if ( signal instanceof BinaryMessage msg ) {
				// 수신된 데이터가 비디오 데이터인 경우, 파일에 기록하고 남은 크기를 갱신한다.
				try {
					byte[] chunk = msg.getBytes();
					m_fos.write(chunk);
					m_remains -= chunk.length;
					s_logger.debug("received video chunk: {}, remains={}", chunk.length, m_remains);

					if ( m_remains <= 0 ) {
						return Optional.of(Transitions.noop(STATE_COMPLETED));
					}
					else {
						long chunkRemains = (m_remains / PSEUDO_CHUNK_SIZE) + 1;
						if ( chunkRemains != m_pseudoChunkRemains ) {
							m_pseudoChunkRemains = chunkRemains;
							s_logger.info("video receiving progress: remains={}", m_remains);
						}
						
						return Optional.of(Transitions.stay());
					}
				}
				catch ( Exception e ) {
					// 비디오 수신 도중 오류가 발생한 경우, 실패 상태로 전이한다.
					getContext().setFailureCause(e);
					return Optional.of(Transitions.noop(STATE_FAILED));
				}
			}
			else if ( signal == STOP ) {
				return Optional.of(Transitions.noop(STATE_CANCELLED));
			}
			else if ( signal instanceof ConnectionClosed ) {
				// 비디오 수신 중 연결이 끊어지더라고 시뮬레이션 자체는 종료된 상태이기 때문에
				// 완료 상태로 전이한다.
				return Optional.of(Transitions.noop(STATE_COMPLETED));
			}
			else {
				return Optional.empty();
			}
		}
	}
	
	/**
	 * 시뮬레이션이 정상 완료된 종료 상태. WebSocket 연결을 정상 종료하고 MDT 상태를 {@code Completed}로 반영한다.
	 */
	private class Completed extends CompletedState<RCKSimulationContext> {
		public Completed(String path, RCKSimulationContext context) {
			super(path, context);
		}

		@Override
		public void enter() {
			super.enter();
			
			stopSimulation();
			s_logger.info("simulation completed");
			
			updateSimulationState(getPath());
		}
	}

	/**
	 * 시뮬레이션이 오류로 종료된 상태. WebSocket 연결을 종료하고 실패 원인을 기록하며 MDT 상태를 {@code Failed}로 반영한다.
	 */
	private class Failed extends ErrorState<RCKSimulationContext> {
		public Failed(String path, RCKSimulationContext context) {
			super(path, context);
		}

		@Override
		public void enter() {
			super.enter();
			
			stopSimulation();
			s_logger.info("simulation failed: cause={}", getContext().getFailureCause());
			
			updateSimulationState(getPath());
		}
	}

	/**
	 * 사용자 요청으로 시뮬레이션이 취소된 종료 상태. WebSocket 연결을 종료하고 MDT 상태를 {@code Cancelled}로 반영한다.
	 */
	private class Cancelled extends CancelledState<RCKSimulationContext> {
		public Cancelled(String path, RCKSimulationContext context) {
			super(path, context);
		}

		@Override
		public void enter() {
			super.enter();
			
			stopSimulation();
			s_logger.info("simulation cancelled");
			
			updateSimulationState(getPath());
		}
	}
	
	/**
	 * 현재 시뮬레이션 상태를 MDT 인스턴스의 {@code State} 출력 인자에 반영한다.
	 * <p>
	 * MDT 인스턴스 매니저에 접속되지 않은 경우({@code m_mdtConnected == false})에는 아무 일도 하지 않는다.
	 * 반영 중 발생한 예외는 경고 로그만 남기고 무시한다 (상태 전이에는 영향을 주지 않는다).
	 *
	 * @param state	반영할 상태 경로 문자열 (예: {@code "/Running"})
	 */
	private void updateSimulationState(String state) {
		if ( m_mdtConnected ) {
			String stateName = STATE_NAMES.getOrDefault(state, state);
			try {
				m_stateRef.updateValue(new StringPropertyValue(stateName));
			}
			catch ( Exception e ) {
				s_logger.warn("failed to update simulation state", e);
			}
		}
	}

	/**
	 * MDT 인스턴스 매니저에 접속하여 시뮬레이션 결과를 반영할 출력 인자 reference들을 준비한다.
	 * <p>
	 * {@code MDT_INSTANCE_ID} 환경변수로 대상 인스턴스를 식별하고, 상태/이용률/결과영상 reference를 활성화한다.
	 * 기존에 첨부되어 있던 결과 영상은 제거한다. 모든 준비가 성공하면 {@code m_mdtConnected}를 {@code true}로 설정한다.
	 * 접속이나 준비 과정에서 예외가 발생하면 경고만 남기고 {@code m_mdtConnected}를 {@code false}로 두어,
	 * 이후 MDT 반영 없이 시뮬레이션만 수행하도록 한다.
	 */
	private void connectMdtInstanceManager() {
		try {
			HttpMDTManager mdt = HttpMDTManager.connectWithDefault();
			m_manager = mdt.getInstanceManager();
			m_instanceId = System.getenv("MDT_INSTANCE_ID");
			if ( m_instanceId == null ) {
				throw new IllegalStateException("MDT_INSTANCE_ID environment variable is not set");
			}

			m_utilizationUpdater = new SimulationOutputUpdater(m_manager, m_instanceId);
			m_utilizationUpdater.update(RCKSimulationResult.empty());

			m_stateRef = SimulationOutputUpdater.getArgumentReference(m_manager, m_instanceId, "State");
			m_videoRef = Try.get(() -> SimulationOutputUpdater.getArgumentReference(m_manager, m_instanceId,
																					"SimulationVideoUrl"))
							.ifSuccessful(ref -> {
								try {
									ref.updateValue(PropertyValue.STRING(""));
								}
								catch ( IOException e ) {
									Throwables.sneakyThrow(e);
								}
							})
							.getOrNull();

			m_mdtConnected = true;
			s_logger.info("connected to MDT Instance: {}", m_instanceId);
		}
		catch ( Exception e ) {
			s_logger.warn("failed to connect MDT InstanceManager: " + e);
			m_mdtConnected = false;
		}
	}
	
	/**
	 * 시뮬레이터에 현재 공정의 시뮬레이션 중지 명령을 전송한다.
	 */
	private void stopSimulation() {
		getLogger().info("requesting simulation stop: {}", getContext().getProcessName());
		
		sendJson(Map.of("type", "stop", "target", getContext().getProcessName()));
	}

	/**
	 * 주어진 JSON 객체를 텍스트 메시지로 직렬화하여 WebSocket으로 송신한다.
	 *
	 * @param kvs	JSON 객체를 구성하는 키-값 쌍의 맵
	 * @throws RuntimeException	JSON 직렬화에 실패한 경우
	 */
	private void sendJson(Map<String,String> kvs) {
		ObjectNode msg = MAPPER.createObjectNode();
		for ( Map.Entry<String,String> kv: kvs.entrySet() ) {
			msg.put(kv.getKey(), kv.getValue());
		}
		sendJson(msg);
	}
	
	/**
	 * 주어진 JSON 객체를 텍스트 메시지로 직렬화하여 WebSocket으로 송신한다.
	 *
	 * @param json	송신할 JSON 객체
	 * @throws RuntimeException	JSON 직렬화에 실패한 경우
	 */
	private void sendJson(ObjectNode json) {
		try {
			sendText(MAPPER.writeValueAsString(json), true);
		}
		catch ( JsonProcessingException e ) {
			throw new RuntimeException("failed to serialize JSON message: " + json, e);
		}
	}

	/**
	 * 시뮬레이션을 단독 실행하기 위한 개발/테스트용 진입점.
	 * <p>
	 * 시뮬레이터 서버 주소·공정명·레이아웃을 지정하여 {@link RCKSimulation}을 생성·구동하고 종료를 기다린다.
	 *
	 * @param args	사용되지 않음
	 * @throws Exception	시뮬레이션 구동 중 오류가 발생한 경우
	 */
	public static final void main(String... args) throws Exception {
        String serverUrl = "wss://press.rck.dscloud.biz";
//        String serverUrl = "ws://localhost:4000/rck/simulator";
        String processName = "press";
//		String layoutName = "프레스 공정_사람+설비추가_NOCON.vcmx";
//		String layoutName = "프레스 공정_AMR_NOCON.vcmx";
//		String layoutName = "프레스 공정_설비추가_NOCON.vcmx";
		String layoutName = "프레스 공정_사람_NOCON.vcmx";
        
		RCKSimulationContext ctxt = new RCKSimulationContext(serverUrl, "mdt", processName, layoutName);
        RCKSimulation sim = new RCKSimulation(ctxt);
        sim.setPingInterval(Duration.ofSeconds(30));
        sim.setPongTimeout(Duration.ofSeconds(5));
        sim.start();
        
        try {
//			sim.waitForFinished(10, TimeUnit.SECONDS);
        	sim.waitForFinished(50, TimeUnit.MINUTES);
			
	        System.out.println("Simulation video url: " + ctxt.getSimulationVideoInfo().downloadUrl());
		}
		catch ( TimeoutException e ) {
	        if ( sim.isRunning() ) {
	        	sim.cancel(true);
	        	sim.waitForFinished();
	        	
	        	return;
			}
		}
	}
}
