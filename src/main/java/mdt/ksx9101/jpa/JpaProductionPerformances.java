package mdt.ksx9101.jpa;

import org.eclipse.digitaltwin.aas4j.v3.model.AasSubmodelElements;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.ProductionPerformances;
import mdt.model.sm.entity.SubmodelElementListEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JpaProductionPerformances
					extends SubmodelElementListEntity<JpaProductionPerformance>
					implements ProductionPerformances {
	public JpaProductionPerformances() {
		setIdShort("ProductionPerformances");
		setOrderRelevant(false);
		setTypeValueListElement(AasSubmodelElements.SUBMODEL_ELEMENT_COLLECTION);
	}

	@Override
	public JpaProductionPerformance newMemberEntity() {
		return new JpaProductionPerformance();
	}
	
	public static class Loader implements JpaEntityLoader<JpaProductionPerformances> {
		@Override
		public JpaProductionPerformances load(EntityManager em, Object key) {
			JpaProductionPerformances entity = new JpaProductionPerformances();
			TypedQuery<JpaProductionPerformance> query
				= em.createQuery("select r from JpaProductionPerformance r", JpaProductionPerformance.class);
			entity.setElementAll(query.getResultList());
			
			return entity;
		}
	}
}
