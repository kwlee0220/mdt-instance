package mdt.ext.rck;

import java.time.Duration;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;

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
	public RCKSimulationOperation(ServiceContext serviceContext, Reference opRef, JavaOperationProviderConfig config) {
		if ( s_logger.isInfoEnabled() ) {
			IdShortPath idShortPath = IdShortPath.fromReference(opRef);
			s_logger.info("AssetConnection (Operation) is ready: op-ref={}", idShortPath);
		}
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		OperationVariables inputs = OperationVariables.fromArray(inputVars);
		
		String endpoint = inputs.readString("RCKServerEndpoint");
		String clientId = "mdt";
		String procName = inputs.readString("ProcessName");
		String layoutName = inputs.readString("LayoutName");
		
		RCKSimulation sim = new RCKSimulation(endpoint, clientId, procName, layoutName);

		Duration pingInterval = Duration.ofSeconds(20);
		Duration pongTimeout = Duration.ofSeconds(60);
		sim.setPingInterval(pingInterval);
		sim.setPongTimeout(pongTimeout);
		
		sim.start();
		RCKSimulationContext ctxt = sim.get();
		
		RCKSimulationResult result = ctxt.getSimulationResult();
		OperationVariables vars = OperationVariables.fromArray(outputVars);
		vars.updateInt("Progress", result.getProgress());
		vars.updateInt("Production", result.getProduction());
		vars.updateString("State", sim.getCurrentState().getPath());
		
		Map<String,FloatPropertyValue> utilMap =  KeyValueFStream.from(result.getUtilizations())
																.mapValue(FloatPropertyValue::new)
																.toMap();
		vars.update("AverageUtilization", new ElementCollectionValue(utilMap));
		
		VideoInfo videoInfo = ctxt.getSimulationVideo();
		vars.updateFile("SimulationVideo", new FileValue(videoInfo.getFileName(), "video/mp4"));
	}
}
