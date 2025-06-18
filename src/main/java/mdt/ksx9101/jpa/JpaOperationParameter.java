package mdt.ksx9101.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mdt.ksx9101.jpa.JpaOperationParameter.Key;
import mdt.model.sm.data.OperationParameter;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
//@Table(name="operation_parameters")
@Table(name="V2_OPERATIONPARAMETER")
@IdClass(Key.class)
@Getter @Setter
public class JpaOperationParameter extends SubmodelElementCollectionEntity
									implements OperationParameter {
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Key {
		private String operationId;
		private String parameterId;
	}
	
	@Id @PropertyField(idShort="OperationID") private String operationId;
	@Id @PropertyField(idShort="ParameterID") private String parameterId;
	@PropertyField(idShort="ParameterName") private String parameterName;
	@PropertyField(idShort="ParameterType") private String parameterType = "String";
	@PropertyField(idShort="ParameterGrade") private String parameterGrade;
	@PropertyField(idShort="ParameterUOMCode") private String parameterUOMCode;
	@PropertyField(idShort="LSL") private String LSL;
	@PropertyField(idShort="USL") private String USL;
	
	@PropertyField(idShort="PeriodicDataCollectionIndicator")
	@Column(name="periodicDataCollectionIndicator")
	private String periodicDataCollectionIndicator;
	@PropertyField(idShort="DataCollectionPeriod")
	private String dataCollectionPeriod;
	
	@Override
	public String getIdShort() {
		return this.parameterId;
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s.%s](%s)", this.getClass().getSimpleName(),
							this.operationId, this.parameterId, this.parameterType);
	}
}
