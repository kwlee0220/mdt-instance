package mdt.persistence;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.ConceptDescription;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.async.Guard;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.operation.OperationHandle;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.operation.OperationResult;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.Page;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.PagingInfo;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotAContainerElementException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.AssetAdministrationShellSearchCriteria;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.ConceptDescriptionSearchCriteria;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelElementSearchCriteria;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelSearchCriteria;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ConcurrentPersistence extends PersistenceStack<ConcurrentPersistenceConfig>
													implements Persistence<ConcurrentPersistenceConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(ConcurrentPersistence.class);
	
	private ConcurrentPersistenceConfig m_config;
	private final Guard m_guard = Guard.create();
	
	public ConcurrentPersistence() {
		setLogger(s_logger);
	}

	@Override
	public void init(CoreConfig coreConfig, ConcurrentPersistenceConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		super.init(coreConfig, config, serviceContext);
		
		m_config = config;
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("initialized {}, config={}", this, config);
		}
	}

	@Override
	public ConcurrentPersistenceConfig asConfig() {
		return m_config;
	}

	@Override
	public Page<SubmodelElement> findSubmodelElements(SubmodelElementSearchCriteria criteria, QueryModifier modifier,
														PagingInfo paging) throws ResourceNotFoundException {
		return getBasePersistence().findSubmodelElements(criteria, modifier, paging);
	}

	@Override
	public SubmodelElement getSubmodelElement(SubmodelElementIdentifier identifier, QueryModifier modifier)
		throws ResourceNotFoundException {
		String paramId = MDTModelLookup.getInstance().getParameterId(identifier);
		if ( paramId != null ) {
			m_guard.lock();
			try {
				return getBasePersistence().getSubmodelElement(identifier, modifier);
			}
			finally {
				m_guard.unlock();
			}
		}
		else {
			return getBasePersistence().getSubmodelElement(identifier, modifier);
		}
	}

	@Override
	public void update(SubmodelElementIdentifier identifier, SubmodelElement submodelElement)
		throws ResourceNotFoundException {
		String paramId = MDTModelLookup.getInstance().getParameterId(identifier);
		if ( paramId != null ) {
			m_guard.runChecked(() -> getBasePersistence().update(identifier, submodelElement));
		}
		else {
			getBasePersistence().update(identifier, submodelElement);
		}
	}

	@Override
	public void insert(SubmodelElementIdentifier parentIdentifier, SubmodelElement submodelElement)
		throws ResourceNotFoundException, ResourceNotAContainerElementException {
		m_guard.lock();
		try {
			getBasePersistence().insert(parentIdentifier, submodelElement);
		}
		finally {
            m_guard.unlock();
		}
	}

	@Override
	public void deleteSubmodel(String id) throws ResourceNotFoundException {
		if ( MDTModelLookup.getInstance().getDataSubmodel().getId().equals(id) ) {
			m_guard.runChecked(() -> getBasePersistence().deleteSubmodel(id));
		}
		else {
			getBasePersistence().deleteSubmodel(id);
		}
	}

	@Override
	public void deleteSubmodelElement(SubmodelElementIdentifier identifier) throws ResourceNotFoundException {
		String paramId = MDTModelLookup.getInstance().getParameterId(identifier);
		if ( paramId != null ) {
			throw new UnsupportedOperationException("Deleting parameter is not supported");
		}
		else {
			getBasePersistence().deleteSubmodelElement(identifier);
		}
	}

	@Override
	public Page<Submodel> findSubmodels(SubmodelSearchCriteria criteria, QueryModifier modifier, PagingInfo paging) {
		return getBasePersistence().findSubmodels(criteria, modifier, paging);
	}

	@Override
	public void save(Submodel submodel) {
		if ( MDTModelLookup.getInstance().getDataSubmodel().getId().equals(submodel.getId()) ) {
			m_guard.runChecked(() -> getBasePersistence().save(submodel));
		}
		else {
			getBasePersistence().save(submodel);
		}
	}

	@Override
	public AssetAdministrationShell getAssetAdministrationShell(String id, QueryModifier modifier)
		throws ResourceNotFoundException {
		return getBasePersistence().getAssetAdministrationShell(id, modifier);
	}

	@Override
	public Page<Reference> getSubmodelRefs(String aasId, PagingInfo paging) throws ResourceNotFoundException {
		return getBasePersistence().getSubmodelRefs(aasId, paging);
	}

	@Override
	public Submodel getSubmodel(String id, QueryModifier modifier) throws ResourceNotFoundException {
		if ( MDTModelLookup.getInstance().getDataSubmodel().getId().equals(id) ) {
			return m_guard.getChecked(() -> getBasePersistence().getSubmodel(id, modifier));
		}
		else {
			return getBasePersistence().getSubmodel(id, modifier);
		}
	}

	@Override
	public ConceptDescription getConceptDescription(String id, QueryModifier modifier)
		throws ResourceNotFoundException {
		return getBasePersistence().getConceptDescription(id, modifier);
	}

	@Override
	public OperationResult getOperationResult(OperationHandle handle) throws ResourceNotFoundException {
		return getBasePersistence().getOperationResult(handle);
	}

	@Override
	public Page<AssetAdministrationShell> findAssetAdministrationShells(AssetAdministrationShellSearchCriteria criteria,
																		QueryModifier modifier, PagingInfo paging) {
		return getBasePersistence().findAssetAdministrationShells(criteria, modifier, paging);
	}

	@Override
	public Page<ConceptDescription> findConceptDescriptions(ConceptDescriptionSearchCriteria criteria,
			QueryModifier modifier, PagingInfo paging) {
		return getBasePersistence().findConceptDescriptions(criteria, modifier, paging);
	}

	@Override
	public void save(AssetAdministrationShell assetAdministrationShell) {
		getBasePersistence().save(assetAdministrationShell);
	}

	@Override
	public void save(ConceptDescription conceptDescription) {
		getBasePersistence().save(conceptDescription);
	}

	@Override
	public void save(OperationHandle handle, OperationResult result) {
		getBasePersistence().save(handle, result);
	}

	@Override
	public void deleteAssetAdministrationShell(String id) throws ResourceNotFoundException {
		getBasePersistence().deleteAssetAdministrationShell(id);
	}

	@Override
	public void deleteConceptDescription(String id) throws ResourceNotFoundException {
		getBasePersistence().deleteConceptDescription(id);
	}
}
