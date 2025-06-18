package mdt.ksx9101.jpa;

import org.eclipse.digitaltwin.aas4j.v3.model.AasSubmodelElements;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.ProductionOrders;
import mdt.model.sm.entity.SubmodelElementListEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JpaProductionOrders extends SubmodelElementListEntity<JpaProductionOrder>
									implements ProductionOrders {
	public JpaProductionOrders() {
		setIdShort("ProductionOrders");
		setOrderRelevant(false);
		setTypeValueListElement(AasSubmodelElements.SUBMODEL_ELEMENT_COLLECTION);
	}

	@Override
	public JpaProductionOrder newMemberEntity() {
		return new JpaProductionOrder();
	}
	
	public static class Loader implements JpaEntityLoader<JpaProductionOrders> {
		@Override
		public JpaProductionOrders load(EntityManager em, Object key) {
			JpaProductionOrders entity = new JpaProductionOrders();
			TypedQuery<JpaProductionOrder> query
					= em.createQuery("select r from JpaProductionOrder r", JpaProductionOrder.class);
			entity.setElementAll(query.getResultList());
			
			return entity;
		}
	}
}
