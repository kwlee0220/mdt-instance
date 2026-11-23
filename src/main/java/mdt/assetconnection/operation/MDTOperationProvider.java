package mdt.assetconnection.operation;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.ExecutionState;
import org.eclipse.digitaltwin.aas4j.v3.model.MessageTypeEnum;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationResult;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetOperationProvider;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetProviderConfig;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.Message;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.operation.OperationHandle;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.util.ReferenceHelper;

import utils.InternalException;
import utils.Preconditions;
import utils.Throwables;
import utils.stream.FStream;

import mdt.config.MDTService;
import mdt.model.MDTModelSerDe;
import mdt.persistence.OperationHandleCaptureLayer;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTOperationProvider implements AssetOperationProvider<MDTOperationProviderConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTOperationProvider.class);

	/**
	 * 비동기 연산 실패 결과에 실리는 오류 메시지의 최대 길이(문자 수).
	 * <p>
	 * FA³ST는 실패 원인의 {@link Throwable#getMessage()}를 연산 결과 메시지에 그대로 넣는다. 메시지가 아주 크면
	 * (예: 파일 내용이 들어간 예외) 클라이언트가 결과 JSON을 읽지 못해 원래 오류 대신 파싱 오류만 보게 되므로,
	 * 이 길이를 넘는 메시지는 잘라서 전달한다. 서버 로그에는 원래 예외가 그대로 남는다.
	 */
	static final int MAX_FAILURE_MESSAGE_LENGTH = 4096;
	
	/** 취소된 연산 결과의 메시지. */
	static final String CANCELLED_MESSAGE = "operation cancelled by request";
	/** 취소된 실행의 결과({@code CANCELED}) 저장을 시도하는 횟수. */
	static final int CANCELLED_SAVE_ATTEMPTS = 3;
	private static final Duration CANCELLED_SAVE_RETRY_DELAY = Duration.ofMillis(100);
	
	/** 서비스의 실행 목록을 얻지 못할 때(FA³ST 서비스 밖에서 쓰는 경우) 연산을 실행할 executor. */
	private static final Executor FALLBACK_EXECUTOR = ForkJoinPool.commonPool();
	
	private final ServiceContext m_serviceContext;
	private final Reference m_opRef;
	private final List<OperationVariable> m_outputVariables;
	private final MDTOperationProviderConfig m_config;
	
	private final OperationProvider m_opProvider;
	
	public MDTOperationProvider(ServiceContext serviceContext, Reference reference,
								MDTOperationProviderConfig config) throws AssetConnectionException {
		Preconditions.checkArgument(config.getJava() != null
									|| config.getProgram() != null
									|| config.getHttp() != null,
									"No operation provider is specified");
		
		m_serviceContext = serviceContext;
		m_opRef = reference;
		m_config = config;
		
        try {
        	m_outputVariables = Lists.newArrayList(m_serviceContext.getOperationOutputVariables(m_opRef)); 
        }
        catch ( ResourceNotFoundException e ) {
        	String msg = String.format("operation not defined in AAS model (reference: %s)",
            							ReferenceHelper.toString(m_opRef));
            throw new IllegalStateException(msg, e);
        }
		catch ( PersistenceException e ) {
			throw new AssetConnectionException(e);
		}
		
        try {
	    	if ( m_config.getJava() != null ) {
	    		m_opProvider = createJavaOperationProvider(serviceContext, m_opRef, m_config.getJava());
	    	}
	    	else if ( m_config.getProgram() != null ) {
				m_opProvider = new ProgramOperationProvider(serviceContext, m_opRef, m_config.getProgram());
	    	}
	    	else if ( m_config.getHttp() != null ) {
	    		m_opProvider = new HttpOperationProvider(serviceContext, m_opRef, m_config.getHttp());
	    	}
	    	else {
	    		throw new AssertionError();
	    	}
        }
        catch ( IOException e ) {
        	throw new AssetConnectionException(e);
        }
	}

	@Override
	public MDTOperationProviderConfig getConfig() {
		return m_config;
	}

	@Override
	public AssetProviderConfig<?> asConfig() {
		return m_config;
	}
	
    @Override
    public OperationVariable[] invoke(OperationVariable[] inputVars,
    								OperationVariable[] inoutputVars) throws AssetConnectionException {
		try {
			OperationVariable[] outputVars = FStream.from(m_outputVariables)
													.mapOrThrow(this::duplicateOperationVariable)
													.toArray(OperationVariable.class);
			m_opProvider.invokeSync(inputVars, inoutputVars, outputVars);

	    	return outputVars;
		}
		catch ( Exception e ) {
			// FA³ST의 동기 호출 처리기는 실패 원인을 로그에도, 응답의 messages에도 남기지 않기 때문에
			// 여기서 기록하지 않으면 원인을 알 수 없다.
			s_logger.error("Failed to invoke operation: op-ref={}", IdShortPath.fromReference(m_opRef), e);
			Throwables.throwIfInstanceOf(e, AssetConnectionException.class);
			throw new AssetConnectionException(e);
		}
    }

	/**
	 * 연산을 비동기로 실행한다.
	 * <p>
	 * 연산 provider의 {@link OperationProvider#invokeSync}를 실행 목록({@link OperationExecutionRegistry})의 전용
	 * 스레드에서 실행하여, 실행 스레드를 인터럽트하는 방식으로 취소할 수 있게 한다.
	 * <p>
	 * FA³ST가 이 호출 직전에 같은 스레드에서 저장한 실행 ID를 {@link OperationHandleCaptureLayer}에서 꺼내 실행을
	 * 그 ID로 등록한다. 실행 ID를 얻지 못하면 실행은 그대로 진행하되 취소할 수 없다.
	 * <p>
	 * 취소된 실행은 FA³ST 콜백을 부르지 않고 {@code CANCELED} 결과를 persistence에 직접 저장한다.
	 */
    @Override
    public void invokeAsync(OperationVariable[] inputVars,
							OperationVariable[] inoutputVars,
							BiConsumer<OperationVariable[], OperationVariable[]> callbackSuccess,
							Consumer<Throwable> callbackFailure)
            throws AssetConnectionException {
		OperationHandle handle = OperationHandleCaptureLayer.takeCapturedHandle();
		String handleId = (handle != null) ? handle.getHandleId() : null;
		String opName = IdShortPath.fromReference(m_opRef).toString();
		OperationExecutionRegistry registry = (m_serviceContext instanceof MDTService svc)
											? svc.getOperationExecutionRegistry() : null;
		boolean registered = false;
		try {
			OperationVariable[] outputVars = FStream.from(m_outputVariables)
													.mapOrThrow(this::duplicateOperationVariable)
													.toArray(OperationVariable.class);
			OperationExecution exec = new OperationExecution(handleId, opName,
					() -> m_opProvider.invokeSync(inputVars, inoutputVars, outputVars),
					() -> callbackSuccess.accept(outputVars, inoutputVars),
					error -> {
						Throwable cause = OperationProvider.toCallbackFailure(error);
						s_logger.error("Failed to invoke AASOperation: op-ref={}, cause={}", opName, ""+cause);
						callbackFailure.accept(limitMessageLength(cause));
					},
					() -> saveCancelled(handle, inoutputVars, opName));

			if ( registry != null && handleId != null ) {
				registry.register(exec);
				registered = true;
			}
			else {
				s_logger.warn("operation handle was not captured; the execution cannot be cancelled: op-ref={}",
								opName);
			}
			exec.start((registry != null) ? registry.getExecutor() : FALLBACK_EXECUTOR);
		}
		catch ( Exception e ) {
			if ( registered ) {
				registry.unregister(handleId);
			}
			s_logger.error("Failed to start operation: op-ref={}", opName, e);
			Throwables.throwIfInstanceOf(e, AssetConnectionException.class);
			throw new AssetConnectionException(e);
		}
    }

    /**
     * 취소된 실행의 결과({@code CANCELED})를 서비스의 최상위 persistence에 저장한다.
     * <p>
     * 저장이 실패하면 {@link #CANCELLED_SAVE_ATTEMPTS}번까지 다시 시도한다. 끝내 실패하면 예외를 던지며,
     * {@link OperationExecution}이 그 원인을 기록한다. 취소 엔드포인트는 이를 응답에 드러내고, 다시 취소 요청을
     * 받으면 저장을 다시 시도한다.
     *
     * @throws PersistenceException	모든 시도가 실패한 경우.
     * @throws InterruptedException	다시 시도하기 전에 기다리는 중 인터럽트된 경우.
     */
    private void saveCancelled(OperationHandle handle, OperationVariable[] inoutputVars, String opName)
		throws PersistenceException, InterruptedException {
		s_logger.info("operation cancelled: op-ref={}, handle={}",
						opName, (handle != null) ? handle.getHandleId() : null);
		if ( handle == null || !(m_serviceContext instanceof MDTService svc) ) {
			return;
		}

		OperationResult result = new DefaultOperationResult.Builder()
										.executionState(ExecutionState.CANCELED)
										.success(false)
										.inoutputArguments(Arrays.asList(inoutputVars))
										.outputArguments(List.of())
										.messages(Message.builder()
														.messageType(MessageTypeEnum.INFO)
														.text(CANCELLED_MESSAGE)
														.build())
										.build();
		for ( int attempt = 1;; ++attempt ) {
			try {
				svc.getPersistence().save(handle, result);
				return;
			}
			catch ( PersistenceException e ) {
				if ( attempt >= CANCELLED_SAVE_ATTEMPTS ) {
					s_logger.error("Failed to save the cancelled operation result: op-ref={}, handle={}, attempts={}",
									opName, handle.getHandleId(), attempt, e);
					throw e;
				}
				s_logger.warn("Failed to save the cancelled operation result, retrying: op-ref={}, handle={}, "
								+ "attempt={}, cause={}", opName, handle.getHandleId(), attempt, ""+e);
				Thread.sleep(CANCELLED_SAVE_RETRY_DELAY.toMillis() * attempt);
			}
		}
    }
    
	/**
	 * 오류 메시지가 {@link #MAX_FAILURE_MESSAGE_LENGTH}를 넘으면 잘린 메시지를 갖는 예외로 감싼다.
	 * <p>
	 * 잘린 메시지 끝에는 {@code "... (<남은 문자 수> more chars)"}를 붙인다. 원래 예외는 감싼 예외의 원인으로
	 * 남는다.
	 *
	 * @param error	실패 원인.
	 * @return	메시지 길이가 제한 이내이면 {@code error} 자신, 아니면 잘린 메시지의 {@link ExecutionException}.
	 */
	static Throwable limitMessageLength(Throwable error) {
		String msg = (error != null) ? error.getMessage() : null;
		if ( msg == null || msg.length() <= MAX_FAILURE_MESSAGE_LENGTH ) {
			return error;
		}

		int end = MAX_FAILURE_MESSAGE_LENGTH;
		if ( Character.isHighSurrogate(msg.charAt(end - 1)) ) {
			--end;	// surrogate pair를 가르지 않는다.
		}
		String limited = String.format("%s... (%d more chars)", msg.substring(0, end), msg.length() - end);
		return new ExecutionException(limited, error);
	}

    private OperationVariable duplicateOperationVariable(OperationVariable var)
    	throws DeserializationException, SerializationException {
    	String jsonStr = MDTModelSerDe.JSON_SERIALIZER.write(var);
    	return MDTModelSerDe.JSON_DESERIALIZER.read(jsonStr, OperationVariable.class);
    }
	
//	private OperationVariable emptyStringPropertyOperationVariable(String idShort) {
//		DefaultProperty defProp = new DefaultProperty.Builder()
//													.idShort(idShort)
//													.valueType(DataTypeDefXsd.STRING)
//													.value("")
//													.build();
//		return new DefaultOperationVariable.Builder().value(defProp).build();
//	}
	
	private OperationProvider createJavaOperationProvider(ServiceContext serviceContext,
                                            			Reference opRef,
                                            			JavaOperationProviderConfig javaOpConfig) {
		try {
			// Java 연산 제공자 객체를 생성한다.
			//
			String opClsName = javaOpConfig.getOperationClassName();
			
			@SuppressWarnings("unchecked")
			Class<? extends OperationProvider> opCls = (Class<? extends OperationProvider>)Class.forName(opClsName);
			Constructor<? extends OperationProvider> ctor = opCls.getConstructor(ServiceContext.class, Reference.class,
																				JavaOperationProviderConfig.class);
			OperationProvider provider = ctor.newInstance(serviceContext, opRef, javaOpConfig);
			
			if ( s_logger.isInfoEnabled() ) {
				IdShortPath idShortPath = IdShortPath.fromReference(opRef);
				s_logger.info("Load JavaOperation: op-ref={}, provider={}",
								idShortPath, javaOpConfig.getOperationClassName());
			}
			return provider;
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to load JavaOperation: class=%s", javaOpConfig.getOperationClassName());
			s_logger.error(msg, cause);
			throw new InternalException(msg, cause);
		}
	}
}
