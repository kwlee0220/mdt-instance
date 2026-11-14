package mdt.assetconnection.operation;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetOperationProvider;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetProviderConfig;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.util.ReferenceHelper;

import utils.InternalException;
import utils.Preconditions;
import utils.Throwables;
import utils.stream.FStream;

import mdt.model.MDTModelSerDe;


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
	
	private final ServiceContext m_serviceContext;
	private final Reference m_opRef;
	private final List<OperationVariable> m_outputVariables;
	private final MDTOperationProviderConfig m_config;
	
	private final OperationProvider m_opProvider;
	
	public MDTOperationProvider(ServiceContext serviceContext, Reference reference,
								MDTOperationProviderConfig config) throws AssetConnectionException {
		Preconditions.checkArgument(config.getJava() != null
									|| config.getProgram() != null
									|| config.getScript() != null
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
	    	else if ( m_config.getScript() != null ) {
	    		m_opProvider = new ScriptOperationProvider(serviceContext, m_opRef, m_config.getScript());
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

    @Override
    public void invokeAsync(OperationVariable[] inputVars,
							OperationVariable[] inoutputVars,
							BiConsumer<OperationVariable[], OperationVariable[]> callbackSuccess,
							Consumer<Throwable> callbackFailure)
            throws AssetConnectionException {
		try {
			OperationVariable[] outputVars = FStream.from(m_outputVariables)
													.mapOrThrow(this::duplicateOperationVariable)
													.toArray(OperationVariable.class);
			Consumer<Throwable> limitedFailure = error -> callbackFailure.accept(limitMessageLength(error));
			m_opProvider.invokeAsync(inputVars, inoutputVars, outputVars, callbackSuccess, limitedFailure);
		}
		catch ( Exception e ) {
			s_logger.error("Failed to start operation: op-ref={}", IdShortPath.fromReference(m_opRef), e);
			Throwables.throwIfInstanceOf(e, AssetConnectionException.class);
			throw new AssetConnectionException(e);
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
