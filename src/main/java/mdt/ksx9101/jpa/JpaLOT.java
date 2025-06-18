package mdt.ksx9101.jpa;

import java.sql.Timestamp;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import mdt.model.sm.data.LOT;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Immutable
//@Entity
//@Table(name="EM_LOT")
@Getter @Setter
public class JpaLOT extends SubmodelElementCollectionEntity implements LOT {
	@PropertyField(idShort="LotId") @Id private String lotId;
	@PropertyField(idShort="ItemId") private String itemId;
	@PropertyField(idShort="State") private String state;
	@PropertyField(idShort="Quantity") private Integer quantity;
	@PropertyField(idShort="OperationId") private String operationId;
	@PropertyField(idShort="EquipmentId") private String equipmentId;
	
	@PropertyField(idShort="StartDateTime")
	@Column(name="STARTDATETIME")
	private Timestamp startDateTime;
	
	@PropertyField(idShort="EndDateTime")
	@Column(name="ENDDATETIME")
	private Timestamp endDateTime;
	
	@PropertyField(idShort="AppliedTactTime")
	@Column(name="APPLIED_TACTTIME")
	private Float appliedTactTime;
	
	@Override
	public String getIdShort() {
		return this.lotId;
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), this.lotId);
	}
}