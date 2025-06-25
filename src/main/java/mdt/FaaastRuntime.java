package mdt;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import utils.func.Funcs;
import utils.func.Lazy;

import mdt.model.ReferenceUtils;
import mdt.model.ResourceNotFoundException;
import mdt.model.sm.value.ElementValues;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.dataformat.DeserializationException;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.http.serialization.HttpJsonApiDeserializer;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.request.SetSubmodelElementValueByPathRequest;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.request.submodel.GetSubmodelElementByPathRequest;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.request.submodel.PutSubmodelElementByPathRequest;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ValueMappingException;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.ElementValue;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.ElementValueParser;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.mapper.ElementValueMapper;
import de.fraunhofer.iosb.ilt.faaast.service.util.ReferenceBuilder;
import de.fraunhofer.iosb.ilt.faaast.service.util.ReferenceHelper;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class FaaastRuntime {
	private final ServiceContext m_service;
	private final Lazy<List<Submodel>> m_submodels = Lazy.of(() -> loadSubmodels());
	
	public FaaastRuntime(ServiceContext service) {
		m_service = service;
	}
	
	public ServiceContext getServiceContext() {
		return m_service;
	}
	
	public List<Submodel> getSubmodels() {
		return m_submodels.get();
	}
	
	public Submodel getSubmodelById(String submodelId) {
		return Funcs.findFirst(m_service.getAASEnvironment().getSubmodels(),
								sm -> submodelId.equals(sm.getId()))
					.getOrThrow(() -> new ResourceNotFoundException("Submodel", "id=" + submodelId));
	}
	
	public Submodel getSubmodelByIdShort(String submodeIdShort) {
		return Funcs.findFirst(m_service.getAASEnvironment().getSubmodels(),
								sm -> submodeIdShort.equals(sm.getIdShort()))
					.getOrThrow(() -> new ResourceNotFoundException("Submodel", "idShort=" + submodeIdShort));
	}
	
	public SubmodelElement getSubmodelElementOfLocation(ElementLocation loc) {
		GetSubmodelElementByPathRequest req = GetSubmodelElementByPathRequest.builder()
																			.submodelId(loc.getSubmodelId())
																			.path(loc.getElementPath())
																			.build();
		return m_service.execute(req).getPayload();
	}
	
	public SubmodelElement getSubmodelElementByPath(String submodelId, String path) {
		GetSubmodelElementByPathRequest req = GetSubmodelElementByPathRequest.builder()
																			.submodelId(submodelId)
																			.path(path)
																			.build();
		return m_service.execute(req).getPayload();
	}
	
	public SubmodelElement getSubmodelElementByReference(Reference reference) {
		ReferenceUtils.assertSubmodelElementReference(reference);
		String submodelId = ReferenceHelper.getRoot(reference).getValue();
		String path = IdShortPath.fromReference(reference).toString();
		
		return getSubmodelElementByPath(submodelId, path);
	}
	
	public void putSubmodelElementByPath(String submodelId, String path, SubmodelElement element) {
		PutSubmodelElementByPathRequest req = PutSubmodelElementByPathRequest.builder()
                                                                            .submodelId(submodelId)
                                                                            .path(path)
                                                                            .submodelElement(element)
                                                                            .build();
		m_service.execute(req);
    }
	
	public void setSubmodelElementValueByPath(String submodelId, String path, String valueString,
												ElementValueParser<Object> valueParser) {
		SetSubmodelElementValueByPathRequest<Object> req = SetSubmodelElementValueByPathRequest.builder()
																		                .path(path)
																		                .submodelId(submodelId)
																		                .value(valueString)
																		                .valueParser(valueParser)
																		                .build();
		m_service.execute(req);
	}
	
	public void setSubmodelElementValueByPath(String submodelId, String path, String valueString) {
		setSubmodelElementValueByPath(submodelId, path, valueString, newElementValueParser(submodelId, path));
	}
	
	public void updateSubmodelElementValue(String submodelId, String elementPath, SubmodelElement element) {
		mdt.model.sm.value.ElementValue smev = ElementValues.getValue(element);
		setSubmodelElementValueByPath(submodelId, elementPath, smev.toValueJsonString());
	}
	
	private List<Submodel> loadSubmodels() {
		return m_service.getAASEnvironment().getSubmodels();
	}

	private static HttpJsonApiDeserializer API_DESERIALIZER = new HttpJsonApiDeserializer();
	private final ElementValueParser<Object> newElementValueParser(String submodelId, String path) {
		return new ElementValueParser<Object>() {
	        @Override
	        public <U extends ElementValue> U parse(Object raw, Class<U> type) throws DeserializationException {
	            String rawString;
				if ( raw.getClass().isAssignableFrom(byte[].class) ) {
					rawString = new String((byte[]) raw);
				}
				else {
					rawString = raw.toString();
				}
	            if ( ElementValue.class.isAssignableFrom(type) ) {
	                try {
	                    return API_DESERIALIZER.readValue(rawString,
								                            m_service.getTypeInfo(new ReferenceBuilder()
											                                            .submodel(submodelId)
											                                            .idShortPath(path)
											                                            .build()));
	                }
					catch ( de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException e ) {
	                    throw new DeserializationException("unable to obtain type information "
	                    									+ "as resource does not exist", e);
					}
	            }
				else if ( SubmodelElement.class.isAssignableFrom(type) ) {
	                SubmodelElement submodelElement = (SubmodelElement) API_DESERIALIZER.read(rawString, type);
	                try {
	                    return ElementValueMapper.toValue(submodelElement, type);
	                }
					catch ( ValueMappingException e ) {
						throw new DeserializationException("error mapping submodel element to value object", e);
					}
	            }
	            throw new DeserializationException(
	                    String.format("error deserializing payload - invalid type '%s' "
	                    				+ "(must be either instance of ElementValue or SubmodelElement",
	                    				type.getSimpleName()));
	        }
	    };
	}
}
