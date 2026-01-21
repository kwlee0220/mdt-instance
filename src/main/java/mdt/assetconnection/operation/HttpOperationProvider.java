package mdt.assetconnection.operation;

import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;

import utils.stream.FStream;

import mdt.client.operation.HttpOperationClient;
import mdt.client.operation.OperationRequest;
import mdt.client.operation.OperationResponse;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
class HttpOperationProvider implements OperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(HttpOperationProvider.class);
	
	@SuppressWarnings("unused")
	private final ServiceContext m_svcContext;
	private final HttpOperationProviderConfig m_config;
	
	HttpOperationProvider(ServiceContext context, Reference operationRef, HttpOperationProviderConfig config) {
		m_svcContext = context;
		m_config = config;
		
		if ( s_logger.isInfoEnabled() ) {
			IdShortPath idShortPath = IdShortPath.fromReference(operationRef);
			s_logger.info("Operation: Http ({}), id={}, poll={}, op-ref={}",
							m_config.getEndpoint(), m_config.getOpId(), m_config.getPollInterval(), idShortPath);
		}
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		Preconditions.checkArgument(inoutputVars == null || inoutputVars.length == 0,
									"inoutput parameters are not supported in HttpOperation");
		OperationRequest req = new OperationRequest();
		req.setOperation(m_config.getOpId());
		req.setInputArguments(toArgumentMap(inputVars));
		req.setInputArguments(toArgumentMap(outputVars));
		req.setAsync(false);
		
		HttpOperationClient client = HttpOperationClient.builder()
														.setEndpoint(m_config.getEndpoint())
														.setRequestBody(req)
														.setPollInterval(m_config.getPollInterval())
														.setTimeout(m_config.getTimeout())
														.build();
		
		OperationResponse resp = client.run();
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("HttpOperation terminates: result=" + resp);
		}
		
		updateOutputVariables(resp, inoutputVars, outputVars);
	}
	
//	public void invokeAsync(OperationVariable[] inputVars,
//							OperationVariable[] inoutputVars,
//							OperationVariable[] outputVars,
//							BiConsumer<OperationVariable[], OperationVariable[]> callbackSuccess,
//							Consumer<Throwable> callbackFailure) throws Exception {
//		HttpTask task = new HttpTask(m_config.getEndpoint(), m_config.getOpId(), m_config.getPollInterval(),
//									false, null);
//		
//		FStream.of(inputVars).map(OperationUtils::toParameter).forEach(task::addOrReplaceInputParameter);
//		FStream.of(inoutputVars).map(OperationUtils::toParameter).forEach(task::addOrReplaceOutputParameter);
//		FStream.of(outputVars).map(OperationUtils::toParameter).forEach(task::addOrReplaceOutputParameter);
//		
//		task.whenFinished(result -> {
//			if ( result.isSuccessful() ) {
//				updateOutputVariables(inoutputVars, outputVars, result.getUnchecked());
//				Try.run(() -> callbackSuccess.accept(inoutputVars, outputVars));
//			}
//			else if ( result.isFailed() ) {
//				Throwable cause = Throwables.unwrapThrowable(result.getCause());
//				if ( cause instanceof RESTfulRemoteException re ) {
//					cause = re.getCause();
//				}
//				else if ( cause instanceof RESTfulIOException re ) {
//					cause = re.getCause();
//				}
//				if ( cause instanceof TimeoutException
//					|| cause instanceof TaskException
//					|| cause instanceof CancellationException
//					|| cause instanceof ExecutionException
//					|| cause instanceof InterruptedException ) {
//					cause = new ExecutionException(cause);
//				}
//				callbackFailure.accept(cause);
//			}
//			else if ( result.isNone() ) {
//				callbackFailure.accept(new CancellationException());
//			}
//		});
//
//		if ( s_logger.isInfoEnabled() ) {
//			s_logger.info("starting HttpOperation: task=" + task);
//		}
//		task.start();
//	}
	
	private Map<String, SubmodelElement> toArgumentMap(OperationVariable[] vars) {
		return FStream.of(vars)
						.map(var -> var.getValue())
						.tagKey(SubmodelElement::getIdShort)
						.toMap();
	}
	
	private void updateOutputVariables(OperationResponse resp, OperationVariable[] inoutputVars,
										OperationVariable[] outputVars) {
		FStream.of(outputVars)
				.tagKey(v -> v.getValue().getIdShort())
				.match(resp.getOutputArguments())
				.forEach(match -> {
					OperationVariable outOpVar = match.value()._1;
					SubmodelElement out = match.value()._2;
					outOpVar.setValue(out);
				});
	}
}
