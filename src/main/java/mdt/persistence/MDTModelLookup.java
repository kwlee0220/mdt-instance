package mdt.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;

import utils.func.Funcs;

import mdt.ElementLocation;
import mdt.model.ResourceNotFoundException;
import mdt.model.sm.SubmodelUtils;


/**
 * {@code MDTModelLookup}는 MDT 시스템에서 사용되는 Submodel 및 SubmodelElement에 대한 조회 기능을 제공한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTModelLookup {
	@SuppressWarnings("unused")
	private static final Logger s_logger = LoggerFactory.getLogger(MDTModelLookup.class);
	
	private static MDTModelLookup s_lookup;
	
	private Map<String,Submodel> m_submodelsByIdShort;	// idShort -> Submodel
	private Map<String,Submodel> m_submodelsById;	    // id -> Submodel
	
	private Submodel m_dataSubmodel;
	private Map<String,String> m_pathToParamIdMap;

	private MDTModelLookup(List<Submodel> submodels) throws ResourceNotFoundException {
		loadMDTModel(submodels);
	}
	
	/**
	 * MDTModelLookup 인스턴스를 반환하거나 새로 생성한다.
	 * <p>
	 * 최초 호출 시에만 Submodel 리스트를 이용하여 MDTModelLookup 인스턴스를 생성하며,
	 * 이후 호출 시에는 기존 인스턴스를 반환한다.
	 *
	 * @param submodels Submodel 리스트
	 * @return MDTModelLookup 인스턴스
	 * @throws ConfigurationInitializationException 초기화 실패 시
	 */
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
	
	/**
	 * 모든 Submodel들을 반환한다.
	 *
	 * @return {@link Submodel} 컬렉션
	 */
	public Collection<Submodel> getSubmodelAll() {
		return m_submodelsByIdShort.values();
	}
	
	public Submodel getSubmodelById(String submodelId) {
		Preconditions.checkState(submodelId != null, "submodelId is null");
		Submodel sm = m_submodelsById.get(submodelId);
		if ( sm == null ) {
			throw new ResourceNotFoundException("Submodel", "id=" + submodelId);
		}
		return sm;
	}
	
	public Submodel getSubmodelByIdShort(String submodelIdShort) {
		Preconditions.checkState(submodelIdShort != null, "submodelIdShort is null");
		
		Submodel sm = m_submodelsByIdShort.get(submodelIdShort);
		if ( sm == null ) {
			throw new ResourceNotFoundException("Submodel", "idShort=" + submodelIdShort);
		}
		return sm;
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
	 * @param identifier    SubmodelElementIdentifier
	 * @return
	 */
	public String getParameterId(SubmodelElementIdentifier identifier) {
		Preconditions.checkState(identifier != null, "SubmodelElementIdentifier is null");
		
		if ( m_dataSubmodel.getId().equals(identifier.getSubmodelId()) ) {
			return m_pathToParamIdMap.get(identifier.getIdShortPath().toString());
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
		
		return m_pathToParamIdMap.get(idShortPath);
	}
	
	public String getIdShortPath(Reference reference) {
		return IdShortPath.fromReference(reference).toString();
	}
	
	public IdShortPath toIdShortPath(String path) {
		return IdShortPath.builder().path(path).build();
	}

	private void loadMDTModel(List<Submodel> submodels) throws ResourceNotFoundException {
		m_submodelsByIdShort = Maps.newHashMap();
		m_submodelsById = Maps.newHashMap();
		for ( Submodel sm : submodels ) {
            m_submodelsByIdShort.put(sm.getIdShort(), sm);
            m_submodelsById.put(sm.getId(), sm);
        }
		
		m_dataSubmodel = Funcs.findFirst(submodels, SubmodelUtils::isDataSubmodel).orElse(null);
		if ( m_dataSubmodel != null ) {
			loadDataSubmodel(m_dataSubmodel);
		}
	}

	private final String EQ_FORMAT = "DataInfo.Equipment.EquipmentParameterValues";
	private final String OP_FORMAT = "DataInfo.Operation.OperationParameterValues";
	private void loadDataSubmodel(Submodel dataSubmodel) {
		String paramsPathFormat = null;
		
		SubmodelElementCollection dataInfo = SubmodelUtils.traverse(dataSubmodel, "DataInfo",
																	SubmodelElementCollection.class);
		if ( SubmodelUtils.containsFieldById(dataInfo, "Equipment") ) {
			paramsPathFormat = EQ_FORMAT;
		}
		else if ( SubmodelUtils.containsFieldById(dataInfo, "Operation") ) {
			paramsPathFormat = OP_FORMAT;
		}
		else {
			throw new IllegalStateException("DataInfo has neither Equipment nor Operation");
		}
        SubmodelElementList parameterValues = SubmodelUtils.traverse(dataSubmodel, paramsPathFormat,
        																SubmodelElementList.class);
        
        m_pathToParamIdMap = Maps.newLinkedHashMap();
        int paramIdx = 0;
		for ( SubmodelElement member : parameterValues.getValue() ) {
            String path = String.format("%s[%d]", paramsPathFormat, paramIdx);
            String paramId = SubmodelUtils.getPropertyFieldById(member, "ParameterID").getValue();
            m_pathToParamIdMap.put(path, paramId);
            ++paramIdx;
		}
	}
}
