package mdt.ksx9101.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import mdt.model.sm.data.ProductionPerformance;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
//@Table(name="production_performances")
@Table(name="V2_PRODUCTIONPERFORMANCE")
@Getter @Setter
public class JpaProductionPerformance extends SubmodelElementCollectionEntity
										implements ProductionPerformance {
//	private String lineId;
	@PropertyField(idShort="ProductionPerformanceID") @Id private String productionPerformanceID;
	@PropertyField(idShort="ProductionPerformanceSequence") private String productionPerformanceSequence;
	@PropertyField(idShort="ProductionOrderID") private String productionOrderID;
	@PropertyField(idShort="ProductionOrderSequence") private String productionOrderSequence;
	@PropertyField(idShort="ItemID") private String itemID;
	@PropertyField(idShort="ItemUOMCode") private String itemUOMCode;
	@PropertyField(idShort="ProducedQuantity") private String producedQuantity;
	@PropertyField(idShort="DefectQuantity") private String defectQuantity;
	@PropertyField(idShort="OperationID") private String operationID;
	@PropertyField(idShort="OperationSequence") private String operationSequence;
	@PropertyField(idShort="ExecutionStartDateTime") private String executionStartDateTime;
	@PropertyField(idShort="ExecutionEndDateTime") private String executionEndDateTime;
	@PropertyField(idShort="LotID") private String lotID;
	
	@Override
	public String getIdShort() {
		return this.productionPerformanceID;
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), this.productionPerformanceID);
	}
}
