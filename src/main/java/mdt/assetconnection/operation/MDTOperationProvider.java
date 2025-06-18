package mdt.assetconnection.operation;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import utils.InternalException;
import utils.Throwables;
import utils.stream.FStream;

import mdt.model.MDTModelSerDe;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetOperationProvider;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.util.ReferenceHelper;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTOperationProvider implements AssetOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTOperationProvider.class);
	
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
			m_opProvider.invokeAsync(inputVars, inoutputVars, outputVars, callbackSuccess, callbackFailure);
		}
		catch ( Exception e ) {
			Throwables.throwIfInstanceOf(e, AssetConnectionException.class);
			throw new AssetConnectionException(e);
		}
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
                                            			Reference opRef, JavaOperationProviderConfig javaOpConfig) {
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
			s_logger.info("{}, cause={}", msg, cause);
			throw new InternalException(msg, cause);
		}
	}
}
