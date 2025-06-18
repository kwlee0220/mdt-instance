package mdt.ksx9101.jpa;

import java.time.Instant;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;

import lombok.Getter;
import lombok.Setter;

import mdt.ksx9101.jpa.JpaEquipmentParameter.Key;
import mdt.model.sm.data.EquipmentParameterValue;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SMElementField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
//@Table(name="equipment_parameter_values")
@Table(name="V2_EQUIPMENTPARAMETERVALUE")
@IdClass(Key.class)
@Getter @Setter
public class JpaEquipmentParameterValue extends SubmodelElementCollectionEntity
										implements EquipmentParameterValue {
	@Id @PropertyField(idShort="EquipmentID")
	private String equipmentId;
	
	@Id @PropertyField(idShort="ParameterID")
	private String parameterId;
	
	@SMElementField(idShort="ParameterValue")
	@Column(name="parameterValue")
	private String parameterValue;
	
	@PropertyField(idShort="EventDateTime")
	private Instant eventDateTime;
	
	@PropertyField(idShort="ValidationResultCode")
	private String validationResultCode;
	
	@Override
	public String getIdShort() {
		return this.parameterId;
	}

	@Override
	public SubmodelElement getParameterValue() {
		return new DefaultProperty.Builder()
								.idShort("ParameterValue")
								.value(this.parameterValue)
								.valueType(DataTypeDefXsd.STRING)
								.build();
	}

	@Override
	public void setParameterValue(SubmodelElement value) {
		if ( value instanceof Property prop ) {
			this.parameterValue = prop.getValue();
		}
		else {
			throw new IllegalArgumentException("Incompatible ParameterValue: not STRING Property: " + value);
		}
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s.%s]=%s", this.getClass().getSimpleName(),
							this.equipmentId, this.parameterId, this.parameterValue);
	}
}
