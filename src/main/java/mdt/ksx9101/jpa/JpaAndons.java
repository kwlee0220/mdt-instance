package mdt.ksx9101.jpa;

import org.eclipse.digitaltwin.aas4j.v3.model.AasSubmodelElements;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.Andons;
import mdt.model.sm.entity.SubmodelElementListEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JpaAndons extends SubmodelElementListEntity<JpaAndon> implements Andons {
	public JpaAndons() {
		setIdShort("Andons");
		setOrderRelevant(false);
		setTypeValueListElement(AasSubmodelElements.SUBMODEL_ELEMENT_COLLECTION);
	}

	@Override
	public JpaAndon newMemberEntity() {
		return new JpaAndon();
	}
	
	public static class Loader implements JpaEntityLoader<JpaAndons> {
		@Override
		public JpaAndons load(EntityManager em, Object key) {
			JpaAndons entity = new JpaAndons();
			TypedQuery<JpaAndon> query = em.createQuery("select r from JpaAndon r", JpaAndon.class);
			entity.setElementAll(query.getResultList());
			
			return entity;
		}
	}
}
