package mdt.ksx9101.jpa;

import com.google.common.base.Preconditions;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import mdt.ksx9101.JpaEntityLoader;
import mdt.model.sm.data.Line;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
//@Table(name="lines")
@Table(name="V2_LINE")
@Getter @Setter
public class JpaLine extends SubmodelElementCollectionEntity implements Line {
	@PropertyField(idShort="LineID") @Id private String lineID;
	@PropertyField(idShort="LineName") private String lineName;
	@PropertyField(idShort="LineType") private String lineType;
	@PropertyField(idShort="UseIndicator") private String useIndicator;
	@PropertyField(idShort="LineStatus") private String lineStatus;

//	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
//	@SMLField(idShort="BOMs", elementClass=DefaultBOM.class)
//	@OneToMany(cascade = CascadeType.PERSIST)
//	@JoinColumn(name="LineID")
//	private List<JpaBOM> BOMs;

//	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
//	@SMLField(idShort="ItemMasters", elementClass=DefaultItemMaster.class)
//	@OneToMany(cascade = CascadeType.PERSIST)
//	@JoinColumn(name="LineID")
//	private List<JpaItemMaster> itemMasters;

//	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
//	@SMLField(idShort="Routings", elementClass=DefaultRouting.class)
//	@OneToMany(cascade = CascadeType.PERSIST)
//	@JoinColumn(name="LineID")
//	private List<JpaRouting> routings;
	
	public JpaLine() {
		setIdShort("Line");
	}

//	@Override
//	public List<BOM> getBOMs() {
//		return FStream.from(this.BOMs).cast(BOM.class).toList();
//	}
//
//	@Override
//	public void setBOMs(List<BOM> boms) {
//		this.BOMs = FStream.from(boms).cast(JpaBOM.class).toList();
//	}
	
//	@Override
//	public List<ItemMaster> getItemMasters() {
//		return FStream.from(this.itemMasters).cast(ItemMaster.class).toList();
//	}
//
//	@Override
//	public void setItemMasters(List<ItemMaster> itemMasters) {
//		this.itemMasters = FStream.from(itemMasters).cast(JpaItemMaster.class).toList();
//	}

//	@Override
//	public List<Routing> getRoutings() {
//		return FStream.from(this.routings).cast(Routing.class).toList();
//	}
//
//	@Override
//	public void setRoutings(List<Routing> routings) {
//		this.routings = FStream.from(routings).cast(JpaRouting.class).toList();
//	}
	
	@Override
	public String toString() {
		return String.format("%s[%s (%s)]", getClass().getSimpleName(), getLineID(), getLineStatus());
	}

	public static class Loader implements JpaEntityLoader<JpaLine> {
		@Override
		public JpaLine load(EntityManager em, Object key) {
			Preconditions.checkArgument(key != null && key instanceof String);
			
			return em.find(JpaLine.class, key);
		}
	}
}
