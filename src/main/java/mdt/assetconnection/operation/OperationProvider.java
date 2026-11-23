package mdt.assetconnection.operation;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Throwables;
import utils.async.AbstractThreadedExecution;

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

	/**
	 * 연산 실행 중 발생한 예외를 FA³ST 실패 콜백에 넘길 예외로 바꾼다.
	 * <p>
	 * 래퍼 예외를 벗겨 원인을 꺼낸다. 원인이 시간 초과·작업 실패·취소·인터럽트 계열이면
	 * {@link ExecutionException}으로 감싼다.
	 *
	 * @param error	연산 실행 중 발생한 예외.
	 * @return	실패 콜백에 넘길 예외.
	 */
	public static Throwable toCallbackFailure(Throwable error) {
		Throwable cause = Throwables.unwrapThrowable(error);
		if ( cause instanceof TimeoutException
			|| cause instanceof TaskException
			|| cause instanceof CancellationException
			|| cause instanceof ExecutionException
			|| cause instanceof InterruptedException ) {
			cause = new ExecutionException(cause);
		}
		return cause;
	}

	/**
	 * 실행을 별도 스레드에서 시작하고 끝날 때까지 기다린다. 기다리는 스레드가 인터럽트되면 실행을 취소한다.
	 * <p>
	 * AAS 비동기 호출의 취소는 provider의 {@code invokeSync}를 실행 중인 스레드의 인터럽트로 전달된다.
	 * 원격 연산을 호출하는 실행(예: {@code RESTfulAsyncRpcClient})은 호출 스레드에서 직접 실행하다 인터럽트되면
	 * 로컬에서만 끝나고 원격 쪽에는 취소를 보내지 않는다. 이 메소드는 인터럽트를 받으면 실행을 {@code cancel(true)}로
	 * 취소하여 원격 취소(예: 세션 {@code DELETE})가 가게 하고, 실행이 끝나기를 기다린 뒤
	 * {@link InterruptedException}을 던진다. 기다리는 중에 다시 인터럽트되면 더 기다리지 않는다.
	 *
	 * @param <T>		실행 결과 타입.
	 * @param execution	아직 시작하지 않은 실행.
	 * @return	실행 결과.
	 * @throws InterruptedException	기다리는 중 인터럽트되어 실행을 취소한 경우.
	 * @throws ExecutionException	실행이 실패한 경우.
	 * @throws CancellationException	실행이 다른 이유로 취소된 경우.
	 */
	public static <T> T runCancellingOnInterrupt(AbstractThreadedExecution<T> execution)
		throws InterruptedException, ExecutionException, CancellationException {
		execution.start();
		try {
			return execution.get();
		}
		catch ( InterruptedException e ) {
			s_logger.info("interrupted; cancelling the execution: {}", execution);
			try {
				execution.cancel(true);
			}
			catch ( Exception cancelFailure ) {
				s_logger.warn("failed to cancel the execution: {}", execution, cancelFailure);
			}
			try {
				execution.get();
			}
			catch ( InterruptedException again ) {
				s_logger.warn("interrupted again; stop waiting for the execution: {}", execution);
			}
			catch ( Exception ignored ) { }
			throw e;
		}
	}
}
