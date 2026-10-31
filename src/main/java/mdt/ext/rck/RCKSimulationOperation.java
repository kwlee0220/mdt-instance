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

import utils.UnitUtils;

import mdt.assetconnection.operation.AbstractJavaOperationProvider;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.ext.rck.VideoMessages.VideoInfo;
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
	private final Duration m_responseTimeout;

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
		
		try {
			String timeoutStr = getStringArgument("responseTimeout");
			m_responseTimeout = (timeoutStr != null) ? UnitUtils.parseDuration(timeoutStr)
													: RCKSimulation.DEFAULT_RESPONSE_TIMEOUT;
		}
		catch ( RuntimeException e ) {
			throw new ConfigurationInitializationException("invalid argument 'responseTimeout': " + e.getMessage());
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
		RCKSimulation sim = new RCKSimulation(ctx, connectMdtOutputs());
		sim.setResponseTimeout(m_responseTimeout);

		Duration pingInterval = Duration.ofSeconds(10);
		Duration pongTimeout = Duration.ofSeconds(2);
		sim.setPingInterval(pingInterval);
		sim.setPongTimeout(pongTimeout);
		
		sim.start();
		// 시뮬레이션이 종료될 때까지 대기한다. (시뮬레이션이 실패한 경우 ExecutionException이 발생한다)
		sim.get();

		RCKSimulationStatus status = ctx.getLatestStatus();
		ElementValues.update(progressProp, PropertyValue.INTEGER(status.getProgress()));
		ElementValues.update(productionProp, PropertyValue.INTEGER(status.getProduction()));
		ElementValues.update(stateProp, PropertyValue.STRING(sim.getCurrentState().getPath()));
		ElementValues.update(avgUtil, MDTSimulationOutputs.toUtilizationJson(status.getAverageUtilizations()));
		
		// 영상 정보를 받기 전에 끝났으면(중지, 영상 대기 중 연결 종료 등) URL은 빈 문자열로 둔다.
		VideoInfo video = ctx.getVideoInfo();
		ElementValues.update(videoUrlProp, (video != null) ? video.downloadUrl() : "");
	}
	
	/**
	 * 시뮬레이션 진행을 이 인스턴스의 출력 인자에 반영할 관찰자를 만든다.
	 * <p>
	 * 인스턴스 식별자({@code MDT_INSTANCE_ID} 환경변수)가 없거나 MDT 인스턴스 매니저에 접속하지 못하면 경고 로그를
	 * 남기고, 진행 상황을 반영하지 않는 관찰자를 돌려준다(시뮬레이션은 그대로 수행한다).
	 */
	private SimulationObserver connectMdtOutputs() {
		String instanceId = System.getenv("MDT_INSTANCE_ID");
		if ( instanceId == null ) {
			s_logger.warn("MDT_INSTANCE_ID is not set; simulation progress is not reported to MDT");
			return SimulationObserver.NONE;
		}
		try {
			MDTSimulationOutputs outputs = MDTSimulationOutputs.connect(instanceId);
			outputs.reset();
			s_logger.info("reporting simulation progress to MDT instance: {}", instanceId);
			
			return outputs;
		}
		catch ( Exception e ) {
			s_logger.warn("failed to connect MDT InstanceManager; simulation progress is not reported: {}", ""+e);
			return SimulationObserver.NONE;
		}
	}
}
