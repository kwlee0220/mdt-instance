package mdt.assetconnection.operation;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Throwables;
import utils.func.Try;

import mdt.task.TaskException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface OperationProvider {
	public static final Logger s_logger = LoggerFactory.getLogger(OperationProvider.class);
	
	public void invokeSync(OperationVariable[] inputVars,
							OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception;

	public default void invokeAsync(OperationVariable[] inputVars,
									OperationVariable[] inoutputVars,
									OperationVariable[] outputVars,
									BiConsumer<OperationVariable[], OperationVariable[]> callbackSuccess,
									Consumer<Throwable> callbackFailure) throws Exception {
		CompletableFuture.runAsync(() -> {
			try {
				this.invokeSync(inputVars, inoutputVars, outputVars);
				Try.run(() -> callbackSuccess.accept(outputVars, inoutputVars));
			}
			catch ( Exception e ) {
				Throwable cause = Throwables.unwrapThrowable(e);
				if ( cause instanceof TimeoutException
					|| cause instanceof TaskException
					|| cause instanceof CancellationException
					|| cause instanceof ExecutionException
					|| cause instanceof InterruptedException ) {
					cause = new ExecutionException(cause);
				}
				s_logger.error("Failed to invoke AASOperation: {}", ""+cause);
				callbackFailure.accept(cause);
			}
		});
	}
}
