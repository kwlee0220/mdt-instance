package mdt.ksx9101.jpa;

import org.eclipse.digitaltwin.aas4j.v3.model.AasSubmodelElements;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.ProductionPlannings;
import mdt.model.sm.entity.SubmodelElementListEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JpaProductionPlannings
						extends SubmodelElementListEntity<JpaProductionPlanning>
						implements ProductionPlannings {
	public JpaProductionPlannings() {
		setIdShort("ProductionPlannings");
		setOrderRelevant(false);
		setTypeValueListElement(AasSubmodelElements.SUBMODEL_ELEMENT_COLLECTION);
	}

	@Override
	public JpaProductionPlanning newMemberEntity() {
		return new JpaProductionPlanning();
	}
	
	public static class Loader implements JpaEntityLoader<JpaProductionPlannings> {
		@Override
		public JpaProductionPlannings load(EntityManager em, Object key) {
			JpaProductionPlannings entity = new JpaProductionPlannings();
			TypedQuery<JpaProductionPlanning> query
					= em.createQuery("select r from JpaProductionPlanning r", JpaProductionPlanning.class);
			entity.setElementAll(query.getResultList());
			
			return entity;
		}
	}
}
