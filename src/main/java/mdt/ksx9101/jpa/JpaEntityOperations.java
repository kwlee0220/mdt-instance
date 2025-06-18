package mdt.ksx9101.jpa;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.func.Funcs;
import utils.stream.FStream;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import mdt.ksx9101.EntityConfiguration;
import mdt.ksx9101.KSX9101PersistencerConfig;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.entity.SubmodelElementEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JpaEntityOperations {
	private static final Logger s_logger = LoggerFactory.getLogger(JpaEntityOperations.class);
	
	private final KSX9101PersistencerConfig m_persistConfig;
	private final EntityManagerFactory m_emf;
	
	public JpaEntityOperations(KSX9101PersistencerConfig persistConfig, EntityManagerFactory emf) {
		m_persistConfig = persistConfig;
		m_emf = emf;
	}
	
	public SubmodelElement read(String idShort) {
		try ( EntityManager em = m_emf.createEntityManager() ) {
			EntityTransaction tx = em.getTransaction();
			tx.begin();
			
			SubmodelElement sme = read(em, idShort);
			tx.commit();
			
			return sme;
		}
	}
	
	// 
	public SubmodelElement read(EntityConfiguration entityConf) {
		try ( EntityManager em = m_emf.createEntityManager() ) {
			EntityTransaction tx = em.getTransaction();
			tx.begin();
			
			SubmodelElement sme = read(em, entityConf);
			tx.commit();
			
			return sme;
		}
	}
	
	public int mount(Submodel initialModel) {
		try ( EntityManager em = m_emf.createEntityManager() ) {
			EntityTransaction tx = em.getTransaction();
			tx.begin();
			
			int count = 0;
			for ( EntityConfiguration config: m_persistConfig.getEntityConfigs() ) {
				String targetSubmodelId = config.getMountPoint().getSubmodel();
				if ( initialModel.getId().equals(targetSubmodelId) ) {
					SubmodelElement sme = read(em, config);
					mountSubmodelElement(config, initialModel, sme);
					++count;
				}
			}
			tx.commit();
			
			return count;
		}
	}
	
	private SubmodelElement read(EntityManager em, String idShort) {
		EntityConfiguration entityConf
				= FStream.from(m_persistConfig.getEntityConfigs())
						.findFirst(conf -> conf.getIdShort().equals(idShort))
						.getOrThrow(() -> new ResourceNotFoundException("SubmodelElement", "idShort="+idShort));
		return read(em, entityConf);
	}
	
	private SubmodelElement read(EntityManager em, EntityConfiguration entityConf) {
		// entityConf에 해당하는 top-level SubmodelElementEntity를 생성하고,
		// 이를 이용하여 top-level SubmodelElement를 얻는다.
		SubmodelElementEntity entity = entityConf.loadJpaEntity(em);
		if ( entity == null ) {
			if ( s_logger.isInfoEnabled() ) {
				s_logger.info("Failed to find TopEntity: {}", entityConf);
			}
			throw new ResourceNotFoundException("TopEntity", entityConf.toString());
		}
		
		SubmodelElement sme = entity.newSubmodelElement();
		return sme;
	}
	
	private void mountSubmodelElement(EntityConfiguration config, Submodel initialModel,
										SubmodelElement extElm) throws ResourceNotFoundException {
		String parentIdShort = config.getMountPoint().getIdShortPath();
		if ( parentIdShort == null || parentIdShort.trim().length() == 0 ) {
			List<SubmodelElement> children = initialModel.getSubmodelElements();
			children.add(extElm);
		}
		else {
			SubmodelElement parent = SubmodelUtils.traverse(initialModel,
															config.getMountPoint().getIdShortPath());
			if ( parent instanceof SubmodelElementCollection smec ) {
				List<SubmodelElement> children = smec.getValue();
				if ( Funcs.exists(children, c -> c.getIdShort().equals(config.getType())) ) {
					throw new ResourceAlreadyExistsException("SubmodelElement", "idShort=Equipment");
				}
				children.add(extElm);
			}
//			else if ( parent instanceof SubmodelElementList smel ) {
//				List<SubmodelElement> children = smel.getValue();
//				if ( Funcs.exists(children, c -> c.getIdShort().equals(config.getType().getSubmodelElementName())) ) {
//					throw new ResourceAlreadyExistsException("SubmodelElement", "idShort=Equipment");
//				}
//				children.add(extElm);
//			}
			else {
				throw new IllegalArgumentException("parent idShortPath: "
													+ config.getMountPoint().getIdShortPath());
			}
		}
	}
}
