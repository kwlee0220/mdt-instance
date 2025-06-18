package mdt.ksx9101.jpa;

import java.util.List;

import com.google.common.base.Preconditions;

import utils.stream.FStream;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.Equipments;
import mdt.model.sm.entity.SubmodelElementListEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JpaEquipments extends SubmodelElementListEntity<JpaEquipment>
							implements Equipments {
	@Override
	public JpaEquipment newMemberEntity() {
		return new JpaEquipment();
	}
	
	private static final String JPQL_FORMAT = "select e from JpaEquipment e where e.equipmentId in %s";
	public static class Loader implements JpaEntityLoader<JpaEquipments> {
		@Override
		public JpaEquipments load(EntityManager em, Object key) {
			Preconditions.checkArgument(key instanceof List
										&& ((List) key).size() > 0
										&& ((List)key).get(0) instanceof String);
			String keysStr = FStream.from((List)key)
									.map(id -> String.format("'%s'", ((String)id).trim()))
									.join(", ", "(", ")");
			String jpql = String.format(JPQL_FORMAT, keysStr);
			
			JpaEquipments entity = new JpaEquipments();
			TypedQuery<JpaEquipment> query = em.createQuery(jpql, JpaEquipment.class);
			entity.setElementAll(query.getResultList());
			
			return entity;
		}
	}
}
