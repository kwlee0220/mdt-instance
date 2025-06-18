package mdt.ksx9101.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import mdt.model.sm.data.Routing;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
//@Table(name="routings")
@Table(name="V2_LINE_ROUTING")
@Getter @Setter
public class JpaRouting extends SubmodelElementCollectionEntity implements Routing {
//	private String lineId;
	@Id @PropertyField(idShort="RoutingID") private String routingID;
	@PropertyField(idShort="RoutingName") private String routingName;
	@PropertyField(idShort="ItemID") private String itemID;
	@PropertyField(idShort="SetupTime") private String setupTime;
	
	@Override
	public String getIdShort() {
		return this.routingID;
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), getItemID());
	}
}