package mdt.ksx9101.jpa;

import org.eclipse.digitaltwin.aas4j.v3.model.AasSubmodelElements;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.Repairs;
import mdt.model.sm.entity.SubmodelElementListEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JpaRepairs extends SubmodelElementListEntity<JpaRepair> implements Repairs {
	public JpaRepairs() {
		setIdShort("Repairs");
		setOrderRelevant(false);
		setTypeValueListElement(AasSubmodelElements.SUBMODEL_ELEMENT_COLLECTION);
	}

	@Override
	public JpaRepair newMemberEntity() {
		return new JpaRepair();
	}
	
	public static class Loader implements JpaEntityLoader<JpaRepairs> {
		@Override
		public JpaRepairs load(EntityManager em, Object key) {
			JpaRepairs entity = new JpaRepairs();
			TypedQuery<JpaRepair> query = em.createQuery("select r from JpaRepair r", JpaRepair.class);
			entity.setElementAll(query.getResultList());
			
			return entity;
		}
	}
}
