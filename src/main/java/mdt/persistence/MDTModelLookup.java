package mdt.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import utils.stream.FStream;

import mdt.ElementLocation;
import mdt.model.ResourceNotFoundException;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.data.DataInfo;
import mdt.model.sm.data.DefaultData;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.PagingInfo;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTModelLookup {
	@SuppressWarnings("unused")
	private static final Logger s_logger = LoggerFactory.getLogger(MDTModelLookup.class);
	
	private static MDTModelLookup s_lookup;
	
	private Map<String,Submodel> m_submodelsByIdShort;
	private Submodel m_dataSubmodel;
	private BiMap<String,String> m_submodelIdToSubmodelIdShortMap;
	private BiMap<String,String> m_submodelIdShortToSubmodelIdMap;
	private BiMap<String,String> m_pathToParameterMap;
	@SuppressWarnings("unused")
	private BiMap<String,String> m_parameterToPathMap;
	
	@SuppressWarnings("rawtypes")
	public static MDTModelLookup getInstanceOrCreate(Persistence perst) throws ConfigurationInitializationException {
		if ( s_lookup == null ) {
			try {
				s_lookup = new MDTModelLookup(perst);
			}
			catch ( ResourceNotFoundException e ) {
				throw new ConfigurationInitializationException(e);
			}
		}
		
		return s_lookup;
	}
	public static MDTModelLookup getInstanceOrCreate(List<Submodel> submodels)
		throws ConfigurationInitializationException {
		if ( s_lookup == null ) {
			try {
				s_lookup = new MDTModelLookup(submodels);
			}
			catch ( ResourceNotFoundException e ) {
				throw new ConfigurationInitializationException(e);
			}
		}
		
		return s_lookup;
	}
	
	public static MDTModelLookup getInstance() {
		if ( s_lookup == null ) {
			throw new IllegalStateException("MDTModelLookup is not initialized");
		}

		return s_lookup;
	}
	
	public Map<String, Submodel> getSubmodelsByIdShort() {
		return m_submodelsByIdShort;
	}
	
	public Collection<Submodel> getSubmodelAll() {
		return m_submodelsByIdShort.values();
	}
	
	public Submodel getDataSubmodel() {
		return m_dataSubmodel;
	}
	
	public SubmodelElement getSubmodelElement(ElementLocation loc) {
		Preconditions.checkArgument(loc != null, "ElementLocation is null");
		
		Submodel submodel = m_submodelsByIdShort.get(loc.getSubmodelIdShort());
		if ( submodel != null ) {
			return SubmodelUtils.traverse(submodel, loc.getElementPath());
		}
		else {
			throw new ResourceNotFoundException("SubmodelElement", "loc=" + loc);
		}
	}

	/**
	 * 주어진 SubmodelElementIdentifier에 해당하는 parameter 식별자를 반환한다.
	 *
	 * @param idShortPath	SubmodelElement의 idShort 경로
	 * @return
	 */
	public String getParameterId(SubmodelElementIdentifier identifier) {
		Preconditions.checkState(identifier != null, "SubmodelElementIdentifier is null");
		
		if ( m_dataSubmodel.getId().equals(identifier.getSubmodelId()) ) {
			return m_pathToParameterMap.get(identifier.getIdShortPath().toString());
		}
		else {
			return null;
		}
	}
	
	/**
	 * 주어진 SubmodelElement의 idShort 경로에 해당하는 parameter 식별자를 반환한다.
	 *
	 * @param idShortPath	SubmodelElement의 idShort 경로
	 * @return
	 */
	public String getParameterId(String idShortPath) {
		Preconditions.checkState(idShortPath != null, "idShortPath is null");
		
		return m_pathToParameterMap.get(idShortPath);
	}
	
	public String getIdShortPath(Reference reference) {
		return IdShortPath.fromReference(reference).toString();
	}
	
	public IdShortPath toIdShortPath(String path) {
		return IdShortPath.builder().path(path).build();
	}
	
	public String getSubmodelIdFromSubmodelIdShort(String submodelIdShort) {
		Preconditions.checkState(submodelIdShort != null, "submodelIdShort is null");

		return m_submodelIdShortToSubmodelIdMap.get(submodelIdShort);
	}
	
	public String getSubmodelIdShortFromSubmodelId(String submodelId) {
		Preconditions.checkState(submodelId != null, "submodelId is null");

		return m_submodelIdToSubmodelIdShortMap.get(submodelId);
	}
	
	private final String EQ_FORMAT = "DataInfo.Equipment.EquipmentParameterValues[%d]";
	private final String OP_FORMAT = "DataInfo.Operation.OperationParameterValues[%d]";

	private MDTModelLookup(List<Submodel> submodels) throws ResourceNotFoundException {
		m_submodelsByIdShort = FStream.from(submodels)
										.tagKey(Submodel::getIdShort)
										.toMap();
		m_dataSubmodel = FStream.from(submodels)
								.findFirst(SubmodelUtils::isDataSubmodel)
								.getOrThrow(() -> new ResourceNotFoundException("Data Submodel"));
		
		m_submodelIdToSubmodelIdShortMap = HashBiMap.create();
		FStream.from(submodels)
				.forEach(sm -> m_submodelIdToSubmodelIdShortMap.put(sm.getId(), sm.getIdShort()));
		m_submodelIdShortToSubmodelIdMap = m_submodelIdToSubmodelIdShortMap.inverse();
		
		m_pathToParameterMap = HashBiMap.create();
		m_parameterToPathMap = m_pathToParameterMap.inverse();
		
		DefaultData data = new DefaultData();
		data.updateFromAasModel(m_dataSubmodel);
		DataInfo dataInfo = data.getDataInfo();
		if ( dataInfo.isEquipment() ) {
			FStream.from(dataInfo.getEquipment().getParameterValueList())
					.zipWithIndex()
					.forEach(idxed -> {
                        String path = String.format(EQ_FORMAT, idxed.index());
                        String paramId = idxed.value().getParameterId();
                        m_pathToParameterMap.put(path, paramId);
                    });
		}
		else if ( dataInfo.isOperation() ) {
			FStream.from(dataInfo.getOperation().getParameterValueList())
					.zipWithIndex()
					.forEach(idxed -> {
                        String path = String.format(OP_FORMAT, idxed.index());
                        String paramId = idxed.value().getParameterId();
                        m_pathToParameterMap.put(path, paramId);
                    });
		}
		else {
			String msg = String.format("DataInfo must have either Equipment or Operation");
			throw new IllegalStateException("Invalid DataInfo: cause=" + msg);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private MDTModelLookup(Persistence perst) throws ResourceNotFoundException {
		this(perst.getAllSubmodels(QueryModifier.DEFAULT, PagingInfo.ALL).getContent());
	}
}
