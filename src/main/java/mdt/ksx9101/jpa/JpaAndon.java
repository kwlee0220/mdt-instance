package mdt.ksx9101.jpa;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import mdt.model.sm.data.Andon;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
//@Table(name="andons")
@Immutable
@Table(name="V2_ANDON")
@Getter @Setter
public class JpaAndon extends SubmodelElementCollectionEntity implements Andon {
//	private String lineId;
	@PropertyField(idShort="AndonID") @Id private Long andonID;
	@PropertyField(idShort="GroupID") private String groupID;
	@PropertyField(idShort="OperationID") private String operationID;
	@PropertyField(idShort="StartDateTime") private String startDateTime;
	@PropertyField(idShort="CauseNO") private String causeNO;
	@PropertyField(idShort="CauseName") private String causeName;
	@PropertyField(idShort="LineStopType") private String lineStopType;
	@PropertyField(idShort="LineStopName") private String lineStopName;
	@PropertyField(idShort="TypeCode") private String typeCode;
	@PropertyField(idShort="TypeName") private String typeName;
	@PropertyField(idShort="EndDateTime") private String endDateTime;
//	@PropertyField(idShort="StopDateTime") private String stopDateTime;
	
	@Override
	public String getIdShort() {
		return "" + this.andonID;
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), this.andonID);
	}
}