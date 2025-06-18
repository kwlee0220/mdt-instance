package mdt.ksx9101.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import mdt.model.sm.data.ProductionOrder;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
//@Table(name="production_orders")
@Table(name="V2_PRODUCTIONORDER")
@Getter @Setter
public class JpaProductionOrder extends SubmodelElementCollectionEntity implements ProductionOrder {
	@PropertyField(idShort="ProductionOrderID") @Id private String productionOrderID;
	@PropertyField(idShort="ProductionOrderSequence") private String productionOrderSequence;

	@PropertyField(idShort="OperationID") private String operationID;
	
	@PropertyField(idShort="ItemID") private String itemID;
	@PropertyField(idShort="ItemUOMCode") private String itemUOMCode;
	@PropertyField(idShort="ProductionOrderQuantity") private String productionOrderQuantity;
	@PropertyField(idShort="ProductionDueDateTime") private String productionDueDateTime;
	@PropertyField(idShort="ScheduleStartDateTime") private String scheduleStartDateTime;
	@PropertyField(idShort="ScheduleEndDateTime") private String scheduleEndDateTime;
	@PropertyField(idShort="SalesDocumentNumber") private String salesDocumentNumber;
	@PropertyField(idShort="SalesDocumentSequence") private String salesDocumentSequence;
	
	@Override
	public String getIdShort() {
		return this.productionOrderID;
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), this.productionOrderID);
	}
}
