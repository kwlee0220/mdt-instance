package mdt.ext.rck;

import java.time.Duration;
import java.util.LinkedHashMap;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;

import utils.func.FOption;
import utils.stream.KeyValueFStream;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.assetconnection.operation.OperationProvider;
import mdt.assetconnection.operation.OperationVariables;
import mdt.model.sm.value.ElementCollectionValue;
import mdt.model.sm.value.FileValue;
import mdt.model.sm.value.PropertyValue.FloatPropertyValue;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationOperation implements OperationProvider {
	private final static Logger s_logger = LoggerFactory.getLogger(RCKSimulationOperation.class);
	
	private static final String DEFAULT_CLIENT_ID = "mdt";
	private static final String DEFAULT_PROCESS_NAME = "press";
	
	private final String m_clientId;
	private final String m_rckServerUrl;
	private final String m_processName;

	public RCKSimulationOperation(ServiceContext serviceContext, Reference opRef,
									JavaOperationProviderConfig config) {
		var args = config.getArguments();
		m_clientId = FOption.ofNullable(args.get("clientId"))
							.map(JsonNode::asText)
							.getOrElse(DEFAULT_CLIENT_ID);
		
		m_processName = FOption.ofNullable(args.get("processName"))
								.map(JsonNode::asText)
								.getOrElse(DEFAULT_PROCESS_NAME);
		
		if ( args.containsKey("rckServerUrl") ) {
			throw new IllegalArgumentException("invalid argument: 'rckServerUrl' is not defined "
												+ "in the arguments of JavaOperationProviderConfig");
		}
		m_rckServerUrl = args.get("rckServerUrl").asText();
		
		if ( s_logger.isInfoEnabled() ) {
			IdShortPath idShortPath = IdShortPath.fromReference(opRef);
			s_logger.info("AssetConnection (Operation) is ready: op-ref={}", idShortPath);
		}
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		OperationVariables inputs = OperationVariables.fromArray(inputVars);
		
//		String endpoint = inputs.readString("RCKServerEndpoint");
//		String procName = inputs.readString("ProcessName");
		String layoutName = inputs.readString("LayoutName");
		
		var ctx = new RCKSimulationContext(m_rckServerUrl, m_clientId, m_processName, layoutName);
		RCKSimulation sim = new RCKSimulation(ctx);

		Duration pingInterval = Duration.ofSeconds(20);
		Duration pongTimeout = Duration.ofSeconds(60);
		sim.setPingInterval(pingInterval);
		sim.setPongTimeout(pongTimeout);
		
		sim.start();
		// 시뮬레이션이 종료될 때까지 대기한다. (시뮬레이션이 실패한 경우 ExecutionException이 발생한다)
		sim.get();

		RCKSimulationResult result = ctx.getSimulationResult();
		OperationVariables vars = OperationVariables.fromArray(outputVars);
		vars.updateInt("Progress", result.getProgress());
		vars.updateInt("Production", result.getProduction());
		vars.updateString("State", sim.getCurrentState().getPath());
		
		var utilMap =  KeyValueFStream.from(result.getUtilizations())
												.mapValue(FloatPropertyValue::new)
												.toMap(new LinkedHashMap<>());
		vars.update("AverageUtilization", new ElementCollectionValue(utilMap));
		
		VideoInfo videoInfo = ctx.getSimulationVideo();
		if ( videoInfo != null ) {
			vars.updateFile("SimulationVideo", new FileValue(videoInfo.getFileName(), "video/mp4"));
		}
		else {
			s_logger.warn("no simulation video available; skip updating 'SimulationVideo' output");
		}
	}
}
