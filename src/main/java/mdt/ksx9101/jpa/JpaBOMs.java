package mdt.ksx9101.jpa;

import org.eclipse.digitaltwin.aas4j.v3.model.AasSubmodelElements;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.BOMs;
import mdt.model.sm.entity.SubmodelElementListEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JpaBOMs extends SubmodelElementListEntity<JpaBOM> implements BOMs {
	public JpaBOMs() {
		setIdShort("BOMs");
		setOrderRelevant(false);
		setTypeValueListElement(AasSubmodelElements.SUBMODEL_ELEMENT_COLLECTION);
	}

	@Override
	public JpaBOM newMemberEntity() {
		return new JpaBOM();
	}
	
	public static class Loader implements JpaEntityLoader<JpaBOMs> {
		@Override
		public JpaBOMs load(EntityManager em, Object key) {
			JpaBOMs entity = new JpaBOMs();
			TypedQuery<JpaBOM> query = em.createQuery("select r from JpaBOM r", JpaBOM.class);
			entity.setElementAll(query.getResultList());
			
			return entity;
		}
	}
}
