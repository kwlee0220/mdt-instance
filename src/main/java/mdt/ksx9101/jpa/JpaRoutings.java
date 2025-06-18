package mdt.ksx9101.jpa;

import org.eclipse.digitaltwin.aas4j.v3.model.AasSubmodelElements;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.Routings;
import mdt.model.sm.entity.SubmodelElementListEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JpaRoutings extends SubmodelElementListEntity<JpaRouting>
							implements Routings {
	public JpaRoutings() {
		setIdShort("Routings");
		setOrderRelevant(false);
		setTypeValueListElement(AasSubmodelElements.SUBMODEL_ELEMENT_COLLECTION);
	}

	@Override
	public JpaRouting newMemberEntity() {
		return new JpaRouting();
	}
	
	public static class Loader implements JpaEntityLoader<JpaRoutings> {
		@Override
		public JpaRoutings load(EntityManager em, Object key) {
			JpaRoutings entity = new JpaRoutings();
			TypedQuery<JpaRouting> query = em.createQuery("select r from JpaRouting r", JpaRouting.class);
			entity.setElementAll(query.getResultList());
			
			return entity;
		}
	}
}
