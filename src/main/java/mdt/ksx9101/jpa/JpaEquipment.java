package mdt.ksx9101.jpa;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.Setter;

import utils.stream.FStream;

import mdt.ksx9101.JpaEntityLoader;
import mdt.model.TopLevelEntity;
import mdt.model.sm.data.Equipment;
import mdt.model.sm.data.Parameter;
import mdt.model.sm.data.ParameterValue;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SMListField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
@Table(name="V2_EQUIPMENT")
@Getter @Setter
public class JpaEquipment extends SubmodelElementCollectionEntity implements Equipment, TopLevelEntity {
	@PropertyField(idShort="EquipmentID") @Id private String equipmentId;
	@PropertyField(idShort="EquipmentName") private String equipmentName;
	@PropertyField(idShort="EquipmentType") private String equipmentType;
	@PropertyField(idShort="UseIndicator") private String useIndicator;

	@SMListField(idShort="EquipmentParameters", elementClass=JpaEquipmentParameter.class)
	@OneToMany(cascade = CascadeType.PERSIST)
	@JoinColumn(name="equipmentId")
	@JsonIgnore
	private List<JpaEquipmentParameter> parameters = Lists.newArrayList();

	@SMListField(idShort="EquipmentParameterValues", elementClass=JpaEquipmentParameterValue.class)
	@OneToMany(cascade = CascadeType.PERSIST)
	@JoinColumn(name="equipmentId")
	@JsonIgnore
	private List<JpaEquipmentParameterValue> parameterValues = Lists.newArrayList();
	
	public JpaEquipment() {
		setIdShort("Equipment");
	}

	@Override
	public List<Parameter> getParameterList() {
		return FStream.from(this.parameters)
						.cast(Parameter.class)
						.toList();
	}

	@Override
	public List<ParameterValue> getParameterValueList() {
		return FStream.from(this.parameterValues)
						.cast(ParameterValue.class)
						.toList();
	}

//	@Override
//	public void update(String idShortPath, Object value) {
//		List<String> pathSegs = SubmodelUtils.parseIdShortPath(idShortPath).toList();
//		
//		String seg0 = pathSegs.get(0);
//		Preconditions.checkArgument("EquipmentParameters".equals(seg0),
//									"'EquipmentParameters' is expected, but={}", seg0);
//		
//		String seg1 = pathSegs.get(1);
//		ParameterValue pvalue;
//		try {
//			int ordinal = Integer.parseInt(seg1);
//			pvalue = this.parameterValues.get(ordinal);
//		}
//		catch ( NumberFormatException e ) {
//			pvalue = Try.get(() -> getParameterValue(seg1)).getOrNull();
//		}
//		FOption.accept(pvalue, pv -> pv.setParameterValue(new PropertyValue((String)value)));
//	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), getEquipmentId());
	}

	public static class Loader implements JpaEntityLoader<JpaEquipment> {
		@Override
		public JpaEquipment load(EntityManager em, Object key) {
			Preconditions.checkArgument(key != null && key instanceof String);
			
			return em.find(JpaEquipment.class, key);
		}
	}
}
