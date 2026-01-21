package mdt.ext.rck;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import utils.func.Try;
import utils.func.Unchecked;
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
import mdt.model.sm.ref.ElementReference;
import mdt.model.sm.ref.ElementReferences;
import mdt.model.sm.ref.MDTArgumentReference;
import mdt.model.sm.value.PropertyValue.StringPropertyValue;


/**
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
	private static final String STATE_RECEIVING_VIDEO = "/ReceivingVideo";
	private static final String STATE_STOPPING = "/Stopping";
	private static final String STATE_COMPLETED = "/Completed";
	private static final String STATE_CANCELLED = "/Cancelled";
	private static final String STATE_FAILED = "/Failed";

	private SimulationOutputUpdater m_utilizationUpdater;
	private MDTArgumentReference m_stateRef;
	private MDTArgumentReference m_videoRef;

	public RCKSimulation(String serverUrl, String clientId, String processName, String layoutName) {
		super(new RCKSimulationContext(serverUrl, clientId, processName, layoutName));
		setLogger(s_logger);
		
		// 상태 등록
		addState(new States.OpenWebSocket<>(STATE_OPEN_WEBSOCKET, getContext(), this,
											STATE_CONNECTING, STATE_FAILED));
		addState(new Connecting(STATE_CONNECTING, getContext()));
		addState(new LayoutLoading(STATE_LAYOUT_LOADING, getContext()));
		addState(new SimulationStarting(STATE_SIMULATION_STARTING, getContext()));
		addState(new Running(STATE_RUNNING, getContext()));
		addState(new ReceivingVideo(STATE_RECEIVING_VIDEO, getContext()));
		addState(new Stopping(STATE_STOPPING, getContext()));
		addState(new CompletedState<>(STATE_COMPLETED, getContext()));
		addState(new ErrorState<>(STATE_FAILED, getContext()));
		addState(new CancelledState<>(STATE_CANCELLED, getContext()));
		
		setInitialState(STATE_OPEN_WEBSOCKET);
		addFinalState(STATE_COMPLETED);
		addFinalState(STATE_FAILED);
		addFinalState(STATE_CANCELLED);
		
		connectMdtInstanceManager();
	}
	
	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		handleSignal(STOP);
		return true;
	}
	
	private static final Signal STOP = new Signal() {};
	
	private static final JsonMapper MAPPER = JsonMapper.builder()
														.addModule(new JavaTimeModule())
														.findAndAddModules()
														.build();

	private abstract class AbstractWaitState extends AbstractState<RCKSimulationContext> {
		abstract protected Transition<RCKSimulationContext> handleMessage(RCKSimulationContext context,
																			JsonNode msg) throws Exception;
		
		protected AbstractWaitState(String name, RCKSimulationContext context) {
			super(name, context);
		}
	
		@Override
		public Optional<Transition<RCKSimulationContext>> selectTransition(Signal signal) {
			if ( signal instanceof TextMessage msg ) {
				try {
					JsonNode jnode = MAPPER.readTree(msg.getMessage());
					return Optional.ofNullable(handleMessage(getContext(), jnode));
				}
				catch ( Exception e ) {
					getContext().setFailureCause(e);
					return Optional.of(Transitions.noop(STATE_FAILED));
				}
			}
			else if ( signal == STOP ) {
				return Optional.of(Transitions.noop(STATE_STOPPING));
			}
			else {
				return Optional.empty();
			}
		}
	}
	
	private class Connecting extends AbstractWaitState {
		protected Connecting(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			requestRegister();
			
			updateSimulationState(getPath());
		}
		
		@Override
		protected Transition<RCKSimulationContext> handleMessage(RCKSimulationContext context, JsonNode payload)
			throws Exception {
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
			String registerMsg = String.format("{\"register\":\"%s\"}", getContext().getClientId());
			sendText(registerMsg, true);
		}
	}
	
	private class LayoutLoading extends AbstractWaitState {
		protected LayoutLoading(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			requestLayout();
			updateSimulationState(getPath());
		}
	
		@Override
		protected Transition<RCKSimulationContext> handleMessage(RCKSimulationContext context, JsonNode payload)
			throws Exception {
			String type = payload.get("type").asText();
	
			if ( type.equals("loading") ) {
				return Transitions.stay();
			}
			// "facility"가 도착하면 layout 정보가 도착한 것이기 때문에
			// layout 정보를 context에 설정하고 시뮬레이션을 시작시킨다.
			else if ( type.equals("facility") ) {
				context.setEquipmentProperties(payload.get("message"));
				return Transitions.noop(STATE_SIMULATION_STARTING);
			}
			else {
				String errorMsg = String.format("Unexpected message type: %s (state=%s)", type, this);
				s_logger.warn(errorMsg);
				
				return null;
			}
		}
		
		private void requestLayout() {
			String SELECT_LAYOUT_MESSAGE_FORMAT = "{ \"type\":\"layout\",\"target\":\"%s\",\"name\":\"%s\" }";
			String msg = String.format(SELECT_LAYOUT_MESSAGE_FORMAT,
										getContext().getProcessName(), getContext().getLayoutName());
			
			sendText(msg, true);
		}
	}
	
	private class SimulationStarting extends AbstractWaitState {
		protected SimulationStarting(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			startSimulation();
			updateSimulationState(getPath());
		}
	
		@Override
		protected Transition<RCKSimulationContext> handleMessage(RCKSimulationContext context, JsonNode payload) throws Exception {
			String type = payload.get("type").asText();
			if ( type.equals("simulation_waiting") ) {
				return Transitions.stay();
			}
			else if ( type.equals("simulation_start") ) {
				return Transitions.noop(STATE_RUNNING);
			}
			else {
				String errorMsg = String.format("Unexpected message type: %s (state=%s)", type, this);
				s_logger.warn(errorMsg);
				
				return null;
			}
		}
		
		private void startSimulation() {
			String START_MESSAGE_FORMAT = "{ \"type\":\"command\",\"target\":\"%s\",\"message\":null }";
			
			RCKSimulationContext context = getContext();
			try {
				String msgTemplate = String.format(START_MESSAGE_FORMAT, context.getProcessName());
				ObjectNode jnode = (ObjectNode)MAPPER.readTree(msgTemplate);
				jnode.set("message", context.getEquipmentProperties());
				String msg = MAPPER.writeValueAsString(jnode);
				sendText(msg, true);
			}
			catch ( Exception e ) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private class Running extends AbstractWaitState {
		
		protected Running(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			if ( m_mdtConnected ) {
				try {
					updateSimulationState(getPath());

					getLogger().info("connected to MDT Instance: {}", m_instanceId);
				}
				catch ( Exception e ) {
					m_mdtConnected = false;
				}
			}
		}
	
		@Override
		protected Transition<RCKSimulationContext> handleMessage(RCKSimulationContext context, JsonNode payload) throws Exception {
			String type = payload.get("type").asText();
			if ( type.equals("simulation_status") ) {
				JsonNode message = payload.get("message");
				RCKSimulationResult result = RCKSimulationResult.parse(message);
				
				if ( m_mdtConnected ) {
					try {
						m_utilizationUpdater.update(result);
					}
					catch ( Exception e ) {
						getLogger().warn("failed to update simulation status", e);
					}
				}
				context.setSimulationResult(result);
				
				return Transitions.stay();
			}
			else if ( type.equals("video_info") ) {
				VideoInfo video = MAPPER.treeToValue(payload, VideoInfo.class);
				context.setSimulationVideo(video);
				
				return Transitions.noop(STATE_RECEIVING_VIDEO);
			}
			else {
				String errorMsg = String.format("Unexpected message type: %s (state=%s)", type, this);
				throw new IllegalStateException(errorMsg);
			}
		}
	}

	private class ReceivingVideo extends AbstractState<RCKSimulationContext> {
		private File m_videoFile;
		private FileOutputStream m_fos;
		private long m_remains;
		
		protected ReceivingVideo(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			try {
				VideoInfo videoInfo = getContext().getSimulationVideo();
				m_videoFile = new File(videoInfo.getFileName());
				m_fos = new FileOutputStream(m_videoFile, false);
				m_remains = videoInfo.getFileSize();
				updateSimulationState(getPath());
			}
			catch ( FileNotFoundException e ) {
				throw new RuntimeException("failed to open output file: " +  m_videoFile);
			}
		}
		
		@Override
		public void exit() {
			IOUtils.closeQuietly(m_fos);
			
			if ( m_remains == 0 ) {
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
			}
		}
	
		@Override
		public Optional<Transition<RCKSimulationContext>> selectTransition(Signal signal) {
			if ( signal instanceof BinaryMessage msg ) {
				try {
					byte[] chunk = msg.getBytes();
					m_fos.write(chunk);
					m_remains -= chunk.length;
					s_logger.info("received video chunk: {}, remains={}", msg.getBytes().length, m_remains);
					if ( m_remains == 0 ) {
						return Optional.of(Transitions.noop(STATE_COMPLETED));
					}
					else {
						return Optional.of(Transitions.stay());
					}
				}
				catch ( Exception e ) {
					getContext().setFailureCause(e);
					return Optional.of(Transitions.noop(STATE_FAILED));
				}
			}
			else if ( signal instanceof ConnectionClosed ) {
				return Optional.of(Transitions.noop(STATE_COMPLETED));
			}
			else {
				return Optional.empty();
			}
		}
	}
	
	private class Stopping extends AbstractWaitState {
		protected Stopping(String path, RCKSimulationContext context) {
			super(path, context);
		}
		
		@Override
		public void enter() {
			String STOP_SIMULATION_MESSAGE_FORMAT = """
				{ "type":"stop","target":"%s" }
			""";
			String msg = String.format(STOP_SIMULATION_MESSAGE_FORMAT, getContext().getProcessName());
			getWebSocket().sendText(msg, true);
			updateSimulationState(getPath());
		}
		
		@Override
		protected Transition<RCKSimulationContext> handleMessage(RCKSimulationContext context, JsonNode payload) throws Exception {
			String type = payload.get("type").asText();
			if ( type.equals("simulation_stop") ) {
				return Transitions.noop(STATE_CANCELLED);
			}
			else if ( type.equals("simulation_status") ) {
				getLogger().info("handling pending simulation_status message during stopping");
				
				// pending되었던 simulation_status 메시지 처리
				JsonNode message = payload.get("message");
				RCKSimulationResult result = RCKSimulationResult.parse(message);
				context.setSimulationResult(result);
				
				return Transitions.stay();
			}
			else {
				String errorMsg = String.format("Unexpected message type: %s (state=%s)", type, this);
				throw new IllegalStateException(errorMsg);
			}
		}
	}
	
	private void updateSimulationState(String state) {
		if ( m_mdtConnected ) {
			try {
				m_stateRef.updateValue(new StringPropertyValue(state));
			}
			catch ( Exception e ) {
				s_logger.warn("failed to update simulation state", e);
			}
		}
	}

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

			m_stateRef = getArgumentReference(m_manager, m_instanceId, "State");
			m_videoRef = Try.get(() -> getArgumentReference(m_manager, m_instanceId, "SimulationVideo"))
							.ifSuccessful(ref -> Unchecked.runOrIgnore(ref::removeAttachment))
							.getOrNull();
			
			m_mdtConnected = true;
		}
		catch ( Exception e ) {
			s_logger.warn("failed to connect MDT InstanceManager: " + e);
			m_mdtConnected = false;
		}
	}
	
	private void stopSimulation() {
		String STOP_SIMULATION_MESSAGE_FORMAT = "{ \"type\":\"stop\",\"target\":\"%s\" }";
		String msg = String.format(STOP_SIMULATION_MESSAGE_FORMAT, getContext().getProcessName());
		sendText(msg, true);
	}
	
	private MDTArgumentReference getArgumentReference(HttpMDTInstanceManager manager, String instId,
														String argName) {
		String argExpr = String.format("oparg:%s:PressSimulation:out:%s", instId, argName);
		ElementReference ref = ElementReferences.parseExpr(argExpr);
		if ( ref instanceof MDTArgumentReference argRef ) {
			argRef.activate(manager);
			return argRef;
		}
		else {
			throw new IllegalArgumentException("Target element is not MDTElementReference: " + ref);
		}
	}
	
	public static final void main(String... args) throws Exception {
        String serverUrl = "ws://59.10.5.215:4000";
//        String serverUrl = "ws://localhost:4000/rck/simulator";
        String processName = "press";
		String layoutName = "프레스 공정_사람+설비추가_NOCON.vcmx";
//		String layoutName = "프레스 공정_AMR_NOCON.vcmx";
//		String layoutName = "프레스 공정_설비추가_NOCON.vcmx";
//		String layoutName = "프레스 공정_사람_NOCON.vcmx";
        
        RCKSimulation sim = new RCKSimulation(serverUrl, "mdt", processName, layoutName);
        sim.setPingInterval(Duration.ofSeconds(20));
        sim.setPongTimeout(Duration.ofSeconds(60));
        sim.start();
        
//        sim.waitForFinished(3, TimeUnit.SECONDS);
//        if ( sim.isRunning() ) {
//        	sim.cancel(true);
//        	sim.waitForFinished();
//        	
//        	return;
//		}
        
        RCKSimulationContext ctxt = sim.get();
        
        File videoFile = new File(ctxt.getSimulationVideo().getFileName());
        System.out.println("video-file: " + videoFile.getAbsolutePath());
        
//        videoFile.delete();
	}
}
