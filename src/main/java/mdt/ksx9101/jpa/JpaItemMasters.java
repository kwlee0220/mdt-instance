package mdt.ksx9101.jpa;

import org.eclipse.digitaltwin.aas4j.v3.model.AasSubmodelElements;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.ItemMasters;
import mdt.model.sm.entity.SubmodelElementListEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JpaItemMasters extends SubmodelElementListEntity<JpaItemMaster>
								implements ItemMasters {
	public JpaItemMasters() {
		setIdShort("ItemMasters");
		setOrderRelevant(false);
		setTypeValueListElement(AasSubmodelElements.SUBMODEL_ELEMENT_COLLECTION);
	}

	@Override
	public JpaItemMaster newMemberEntity() {
		return new JpaItemMaster();
	}
	
	public static class Loader implements JpaEntityLoader<JpaItemMasters> {
		@Override
		public JpaItemMasters load(EntityManager em, Object key) {
			JpaItemMasters entity = new JpaItemMasters();
			TypedQuery<JpaItemMaster> query = em.createQuery("select r from JpaItemMaster r", JpaItemMaster.class);
			entity.setElementAll(query.getResultList());
			
			return entity;
		}
	}
}
