package mdt.persistence;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.ConceptDescription;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import utils.LoggerSettable;
import utils.Utilities;
import utils.func.FOption;

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
import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelElementSearchCriteria;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelSearchCriteria;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class PersistenceStack<C extends PersistenceStackConfig<?>>
																implements Persistence<C>, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(PersistenceStack.class);
	
	private Persistence m_basePersistence;
	private Logger m_logger = s_logger;

	public PersistenceStack() {
	}

	@Override
	public void init(CoreConfig coreConfig, C config, ServiceContext serviceContext)
																		throws ConfigurationInitializationException {
		PersistenceConfig<?> baseConfig = config.getBasePersistenceConfig();
		Preconditions.checkState(baseConfig != null, "%s: Base persistence config is null", getClass().getName());
		
		String name = baseConfig.getClass().getName();
		int idx = name.lastIndexOf('C');
		m_basePersistence = Utilities.newInstance(name.substring(0, idx), Persistence.class);
		m_basePersistence.init(coreConfig, baseConfig, serviceContext);
		
		if ( !(m_basePersistence instanceof PersistenceStack) ) {
			MDTModelLookup.getInstanceOrCreate(m_basePersistence);
		}
	}
	
	public Persistence<?> getBasePersistence() {
		return m_basePersistence;
	}
	
	public void setBasePersistence(Persistence<?> base) {
		m_basePersistence = base;
	}

	@Override
	public AssetAdministrationShell getAssetAdministrationShell(String id, QueryModifier modifier)
																					throws ResourceNotFoundException {
		return m_basePersistence.getAssetAdministrationShell(id, modifier);
	}

	@Override
	public Page<Reference> getSubmodelRefs(String aasId, PagingInfo paging) throws ResourceNotFoundException {
		return m_basePersistence.getSubmodelRefs(aasId, paging);
	}

	@Override
	public Submodel getSubmodel(String id, QueryModifier modifier) throws ResourceNotFoundException {
		return m_basePersistence.getSubmodel(id, modifier);
	}

	@Override
	public ConceptDescription getConceptDescription(String id, QueryModifier modifier) throws ResourceNotFoundException {
		return m_basePersistence.getConceptDescription(id, modifier);
	}

	@Override
	public SubmodelElement getSubmodelElement(SubmodelElementIdentifier identifier, QueryModifier modifier)
																				throws ResourceNotFoundException {
		return m_basePersistence.getSubmodelElement(identifier, modifier);
	}

	@Override
	public OperationResult getOperationResult(OperationHandle handle) throws ResourceNotFoundException {
		return m_basePersistence.getOperationResult(handle);
	}

	@Override
	public Page<AssetAdministrationShell> findAssetAdministrationShells(AssetAdministrationShellSearchCriteria criteria,
																		QueryModifier modifier, PagingInfo paging) {
		return m_basePersistence.findAssetAdministrationShells(criteria, modifier, paging);
	}

	@Override
	public Page<Submodel> findSubmodels(SubmodelSearchCriteria criteria, QueryModifier modifier, PagingInfo paging) {
		return m_basePersistence.findSubmodels(criteria, modifier, paging);
	}

	@Override
	public Page<SubmodelElement> findSubmodelElements(SubmodelElementSearchCriteria criteria, QueryModifier modifier,
														PagingInfo paging) throws ResourceNotFoundException {
		return m_basePersistence.findSubmodelElements(criteria, modifier, paging);
	}

	@Override
	public Page<ConceptDescription> findConceptDescriptions(ConceptDescriptionSearchCriteria criteria,
															QueryModifier modifier, PagingInfo paging) {
		return m_basePersistence.findConceptDescriptions(criteria, modifier, paging);
	}

	@Override
	public void save(AssetAdministrationShell assetAdministrationShell) {
		m_basePersistence.save(assetAdministrationShell);
	}

	@Override
	public void save(ConceptDescription conceptDescription) {
		m_basePersistence.save(conceptDescription);
	}

	@Override
	public void save(Submodel submodel) {
		m_basePersistence.save(submodel);
	}

	@Override
	public void insert(SubmodelElementIdentifier parentIdentifier, SubmodelElement submodelElement)
											throws ResourceNotFoundException, ResourceNotAContainerElementException {
		m_basePersistence.insert(parentIdentifier, submodelElement);
	}

	@Override
	public void update(SubmodelElementIdentifier identifier, SubmodelElement submodelElement)
																				throws ResourceNotFoundException {
		m_basePersistence.update(identifier, submodelElement);
	}

	@Override
	public void save(OperationHandle handle, OperationResult result) {
		m_basePersistence.save(handle, result);
	}

	@Override
	public void deleteAssetAdministrationShell(String id) throws ResourceNotFoundException {
		m_basePersistence.deleteAssetAdministrationShell(id);
	}

	@Override
	public void deleteSubmodel(String id) throws ResourceNotFoundException {
		m_basePersistence.deleteSubmodel(id);
	}

	@Override
	public void deleteConceptDescription(String id) throws ResourceNotFoundException {
		m_basePersistence.deleteConceptDescription(id);
	}

	@Override
	public void deleteSubmodelElement(SubmodelElementIdentifier identifier) throws ResourceNotFoundException {
		m_basePersistence.deleteSubmodelElement(identifier);
	}

	@Override
	public Logger getLogger() {
		return m_logger;
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = FOption.getOrElse(logger, s_logger);
	}
}
