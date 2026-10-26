package mdt.ext.rck;

import java.time.Duration;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;

import utils.json.JacksonUtils;

import mdt.assetconnection.operation.AbstractJavaOperationProvider;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.value.PropertyValue;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationOperation extends AbstractJavaOperationProvider {
	private final static Logger s_logger = LoggerFactory.getLogger(RCKSimulationOperation.class);
	
	private final String m_clientId;
	private final String m_rckServerUrl;
	private final String m_processName;

	public RCKSimulationOperation(ServiceContext serviceContext, Reference opRef,
									JavaOperationProviderConfig config)
		throws ConfigurationInitializationException {
		super(serviceContext, opRef, config);
		
		m_clientId = getStringArgument("clientId");
		if ( m_clientId == null || m_clientId.isEmpty() ) {
			throw new ConfigurationInitializationException("invalid argument: 'clientId' is not defined "
														+ "in the arguments of JavaOperationProviderConfig");
		}
		
		m_processName = getStringArgument("processName");
		if ( m_processName == null || m_processName.isEmpty() ) {
			throw new ConfigurationInitializationException("invalid argument: 'processName' is not defined "
														+ "in the arguments of JavaOperationProviderConfig");
		}
		
		m_rckServerUrl = getStringArgument("rckServerUrl");
		if ( m_rckServerUrl == null || m_rckServerUrl.isEmpty() ) {
			throw new ConfigurationInitializationException("invalid argument: 'rckServerUrl' is not defined "
														+ "in the arguments of JavaOperationProviderConfig");
		}
		
		if ( s_logger.isInfoEnabled() ) {
			IdShortPath idShortPath = IdShortPath.fromReference(opRef);
			s_logger.info("Operation (path={}) is ready: clientId={}, processName={}, rckServerUrl={}",
							idShortPath, m_clientId, m_processName, m_rckServerUrl);
		}
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
								Map<String, SubmodelElement> inoutputVarList,
								Map<String, SubmodelElement> outputVarList) throws Exception {
		String layoutName = getRequiredStringVariableValue(inputVarList, "LayoutName");
		if ( layoutName == null || layoutName.isEmpty() ) {
			throw new ConfigurationInitializationException("invalid argument: 'LayoutName' is not defined "
														+ "in the arguments of JavaOperationProviderConfig");
		}
		
		Property progressProp = getPropertyVariable(outputVarList, "Progress", DataTypeDefXsd.INT);
		Property productionProp = getPropertyVariable(outputVarList, "Production", DataTypeDefXsd.INT);
		Property stateProp = getPropertyVariable(outputVarList, "FinalState", DataTypeDefXsd.STRING);
		Property avgUtil = getPropertyVariable(outputVarList, "AverageUtilization", DataTypeDefXsd.STRING);
		Property videoUrlProp = getPropertyVariable(outputVarList, "SimulationVideoUrl", DataTypeDefXsd.STRING);
		
		var ctx = new RCKSimulationContext(m_rckServerUrl, m_clientId, m_processName, layoutName);
		RCKSimulation sim = new RCKSimulation(ctx);

		Duration pingInterval = Duration.ofSeconds(10);
		Duration pongTimeout = Duration.ofSeconds(2);
		sim.setPingInterval(pingInterval);
		sim.setPongTimeout(pongTimeout);
		
		sim.start();
		// 시뮬레이션이 종료될 때까지 대기한다. (시뮬레이션이 실패한 경우 ExecutionException이 발생한다)
		sim.get();

		RCKSimulationResult result = ctx.getSimulationResult();
		ElementValues.update(progressProp, PropertyValue.INTEGER(result.getProgress()));
		ElementValues.update(productionProp, PropertyValue.INTEGER(result.getProduction()));
		ElementValues.update(stateProp, PropertyValue.STRING(sim.getCurrentState().getPath()));
		
		String utilsJson = JacksonUtils.toObjectNodeFloats(result.getUtilizations()).toPrettyString();
		ElementValues.update(avgUtil, utilsJson);
		
		ElementValues.update(videoUrlProp, ctx.getSimulationVideoInfo().downloadUrl());
	}
}
