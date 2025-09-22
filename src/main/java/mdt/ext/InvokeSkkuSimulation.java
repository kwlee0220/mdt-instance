package mdt.ext;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.concurrent.GuardedBy;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.async.AsyncState;
import utils.async.Guard;
import utils.http.OkHttpClientUtils;
import utils.stream.FStream;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.assetconnection.operation.OperationProvider;
import mdt.client.operation.HttpSimulationClient;
import mdt.client.operation.OperationStatus;
import mdt.client.operation.OperationStatusResponse;
import mdt.config.MDTInstanceConfig;
import mdt.config.MDTServiceContext;
import mdt.model.ResourceNotFoundException;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.simulation.Simulation;
import mdt.task.TaskException;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import okhttp3.OkHttpClient;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class InvokeSkkuSimulation implements OperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(InvokeSkkuSimulation.class);
	
	private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(3);
	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
	
	private final ServiceContext m_svcContext;
	private final Reference m_opRef;
	private Duration m_timeout = DEFAULT_TIMEOUT;
	private Duration m_pollInterval = DEFAULT_POLL_INTERVAL;

	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private AsyncState m_status = AsyncState.NOT_STARTED;
	
	public InvokeSkkuSimulation(ServiceContext serviceContext, Reference opRef, JavaOperationProviderConfig config) {
		if ( s_logger.isInfoEnabled() ) {
			IdShortPath idShortPath = IdShortPath.fromReference(opRef);
			s_logger.info("AssetConnection (RunSkkuSimulation Operation) is ready: op-ref={}", idShortPath);
		}
		
		m_svcContext = serviceContext;
		m_opRef = opRef;
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		String smId = m_opRef.getKeys().getFirst().getValue();
		Submodel simulation = FStream.from(m_svcContext.getAASEnvironment().getSubmodels())
									.findFirst(sm -> smId.equals(sm.getId()))
									.getOrThrow(() -> new ResourceNotFoundException("Submodel", "id=" + smId));

		run(simulation);
	}
	
	private void run(Submodel simulation) throws InterruptedException, KeyManagementException,
												NoSuchAlgorithmException, TimeoutException, TaskException {
		if ( !Simulation.SEMANTIC_ID_REFERENCE.equals(simulation.getSemanticId()) ) {
			String msg = String.format("The target Submode is not for a Simulation: id=", simulation.getId());
			throw new IllegalArgumentException(msg);
		}
		
		String simulatorEndpoint = SubmodelUtils.getPropertyValueByPath(simulation,
																		Simulation.IDSHORT_PATH_ENDPOINT,
																		String.class);
		if ( simulatorEndpoint == null || simulatorEndpoint.trim().length() == 0 ) {
			System.err.printf("Simulator Endpoint is missing: submodel-id=%s%n", simulation.getId());
			System.exit(-1);
		}
		
		OkHttpClient httpClient = OkHttpClientUtils.newTrustAllOkHttpClientBuilder().build();
		HttpSimulationClient client = new HttpSimulationClient(httpClient, simulatorEndpoint);
		client.setLogger(s_logger);

		Instant started = Instant.now();
		MDTInstanceConfig instConfig = ((MDTServiceContext)m_svcContext).getInstanceConfig();
		String simulationSubmodelEndpoint = instConfig.getSubmodelEndpoint(simulation.getId());
		OperationStatusResponse<Void> resp = client.startSimulationWithEndpoint(simulationSubmodelEndpoint);
		
		m_guard.run(() -> m_status = AsyncState.RUNNING);
		
		String location = resp.getOperationLocation();
		while ( resp.getStatus() == OperationStatus.RUNNING ) {
			TimeUnit.MILLISECONDS.sleep(m_pollInterval.toMillis());
			
			m_guard.lock();
			try {
				if ( m_status == AsyncState.CANCELLING ) {
					resp = client.cancelSimulation(location);
					if ( resp.getStatus() == OperationStatus.CANCELLED ) {
						m_status = AsyncState.CANCELLED;
						m_guard.signalAll();
						throw new CancellationException(resp.getMessage());
					}
				}
			}
			finally {
				m_guard.unlock();
			}
			
			resp = client.statusSimulation(location);
			if ( m_timeout != null && resp.getStatus() == OperationStatus.RUNNING ) {
				if ( m_timeout.minus(Duration.between(started, Instant.now())).isNegative() ) {
					client.cancelSimulation(location);
					m_guard.run(() -> m_status = AsyncState.FAILED);
					
					throw new TimeoutException("Timeout expired: " + m_timeout);
				}
			}
		}
		
		switch ( resp.getStatus() ) {
			case COMPLETED:
				return;
			case FAILED:
				throw new TaskException(new Exception(resp.getMessage()));
			case CANCELLED:
				throw new CancellationException(resp.getMessage());
			default:
				throw new AssertionError();
		}
	}
}
