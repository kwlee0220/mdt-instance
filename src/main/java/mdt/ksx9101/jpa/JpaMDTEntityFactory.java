package mdt.ksx9101.jpa;

import java.util.Map;

import com.google.common.collect.Maps;

import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.entity.SubmodelElementEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JpaMDTEntityFactory {
	private Map<String,JpaEntityLoader> FQCN_TO_ADAPTORS = Maps.newHashMap();
	
	public JpaMDTEntityFactory() {
		FQCN_TO_ADAPTORS.put(JpaEquipment.class.getName(), new JpaEquipment.Loader());
		FQCN_TO_ADAPTORS.put(JpaOperation.class.getName(), new JpaOperation.Loader());
		FQCN_TO_ADAPTORS.put(JpaLine.class.getName(), new JpaLine.Loader());
		FQCN_TO_ADAPTORS.put(JpaProductionPlannings.class.getName(), new JpaProductionPlannings.Loader());
		FQCN_TO_ADAPTORS.put(JpaProductionOrders.class.getName(), new JpaProductionOrders.Loader());
		FQCN_TO_ADAPTORS.put(JpaProductionPerformances.class.getName(), new JpaProductionPerformances.Loader());
		FQCN_TO_ADAPTORS.put(JpaRepairs.class.getName(), new JpaRepairs.Loader());
		FQCN_TO_ADAPTORS.put(JpaAndons.class.getName(), new JpaAndons.Loader());
		FQCN_TO_ADAPTORS.put(JpaBOMs.class.getName(), new JpaBOMs.Loader());
		FQCN_TO_ADAPTORS.put(JpaItemMasters.class.getName(), new JpaItemMasters.Loader());
		FQCN_TO_ADAPTORS.put(JpaRoutings.class.getName(), new JpaRoutings.Loader());
	}
	
	public JpaEntityLoader newInstance(String id) {
		JpaEntityLoader<? extends SubmodelElementEntity> loader = FQCN_TO_ADAPTORS.get(id);
		if ( loader == null ) {
			throw new IllegalArgumentException("Unknown MDTEntity: id=" + id);
		}
		
		return (JpaEntityLoader)loader;
	}
}
