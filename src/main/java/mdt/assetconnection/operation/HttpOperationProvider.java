package mdt.assetconnection.operation;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.stream.FStream;
import utils.stream.KeyValueFStream;

import mdt.client.operation.HttpOperationClient;
import mdt.client.operation.OperationRequest;
import mdt.client.operation.OperationResponse;
import mdt.client.operation.OperationUtils;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.variable.AbstractVariable.ElementVariable;
import mdt.model.sm.variable.AbstractVariable.ValueVariable;
import mdt.model.sm.variable.Variable;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;


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
		OperationRequest req = new OperationRequest();
		req.setOperation(m_config.getOpId());
		FStream.of(inputVars).map(OperationUtils::toTaskPort).forEach(req.getInputVariables()::add);
		FStream.of(inoutputVars).map(OperationUtils::toTaskPort).forEach(req.getInputVariables()::add);
		FStream.of(outputVars).map(OperationUtils::toTaskPort).forEach(req.getOutputVariables()::add);
		FStream.of(inoutputVars).map(OperationUtils::toTaskPort).forEach(req.getOutputVariables()::add);
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
	
	private void updateOutputVariables(OperationResponse resp, OperationVariable[] inoutputVars,
										OperationVariable[] outputVars) {
		FStream.of(inoutputVars)
				.concatWith(FStream.of(outputVars))
				.tagKey(v -> v.getValue().getIdShort())
				.innerJoin(KeyValueFStream.from(FStream.from(resp.getResult()).tagKey(Variable::getName)))
				.forEach(match -> {
					OperationVariable outOpVar = match.value()._1;
					Variable outVar = match.value()._2;
					
					if ( outVar instanceof ValueVariable vvar ) {
						ElementValues.update(outOpVar.getValue(), vvar.readValue());
					}
					else if ( outVar instanceof ElementVariable elmVar ) {
						outOpVar.setValue(elmVar.read());
					}
					else {
						throw new IllegalArgumentException("Unsupported output variable: " + outVar);
					}
				});
	}
}
