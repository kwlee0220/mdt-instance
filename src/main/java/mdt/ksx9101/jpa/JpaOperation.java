package mdt.ksx9101.jpa;

import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import utils.stream.FStream;

import mdt.ksx9101.JpaEntityLoader;
import mdt.model.TopLevelEntity;
import mdt.model.sm.data.Operation;
import mdt.model.sm.data.Parameter;
import mdt.model.sm.data.ParameterValue;
import mdt.model.sm.data.ProductionOrder;
import mdt.model.sm.entity.PropertyField;
import mdt.model.sm.entity.SMListField;
import mdt.model.sm.entity.SubmodelElementCollectionEntity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
//@Table(name="operations")
@Table(name="V2_OPERATION")
@Getter @Setter
public class JpaOperation extends SubmodelElementCollectionEntity implements Operation, TopLevelEntity {
	@Id @PropertyField(idShort="OperationID") private String operationId;
	@PropertyField(idShort="OperationName") private String operationName;
	@PropertyField(idShort="OperationType") private String operationType;
	@PropertyField(idShort="UseIndicator") private String useIndicator;

	@SMListField(idShort="ProductionOrders", elementClass=JpaProductionOrder.class)
	@OneToMany(fetch=FetchType.EAGER, mappedBy="operationID")
	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
	private List<JpaProductionOrder> productionOrders = Lists.newArrayList();

	@SMListField(idShort="OperationParameters", elementClass=JpaOperationParameter.class)
	@OneToMany(cascade = CascadeType.PERSIST)
	@JoinColumn(name="operationId")
	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
	private List<JpaOperationParameter> parameterList;

	@SMListField(idShort="OperationParameterValues", elementClass=JpaOperationParameterValue.class)
	@OneToMany(cascade = CascadeType.PERSIST)
	@JoinColumn(name="operationId")
	@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
	private List<JpaOperationParameterValue> parameterValueList;
	
	public JpaOperation() {
		setIdShort("Operation");
	}

	@Override
	public List<Parameter> getParameterList() {
		return FStream.from(this.parameterList)
						.cast(Parameter.class)
						.toList();
	}

	@Override
	public List<ParameterValue> getParameterValueList() {
		return FStream.from(this.parameterValueList)
						.cast(ParameterValue.class)
						.toList();
	}
	@Override
	public List<ProductionOrder> getProductionOrders() {
		return FStream.from(this.productionOrders).cast(ProductionOrder.class).toList();
	}

	@Override
	public void setProductionOrders(List<ProductionOrder> orders) {
		this.productionOrders = FStream.from(orders)
										.cast(JpaProductionOrder.class)
										.toList();
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), getOperationId());
	}


//	@Override
//	public void update(String idShortPath, Object value) {
//		List<String> pathSegs = SubmodelUtils.parseIdShortPath(idShortPath).toList();
//		
//		String seg0 = pathSegs.get(0);
//		Preconditions.checkArgument("OperationParameters".equals(seg0),
//									"'OperationParameters' is expected, but={}", seg0);
//		
//		String seg1 = pathSegs.get(1);
//		ParameterValue pvalue;
//		try {
//			int ordinal = Integer.parseInt(seg1);
//			pvalue = this.parameterValueList.get(ordinal);
//		}
//		catch ( NumberFormatException e ) {
//			pvalue = Try.get(() -> getParameterValue(seg1)).getOrNull();
//		}
//		Funcs.runIfNotNull(pvalue, pv -> pv.setParameterValue(new PropertyValue((String)value)));
//	}
	
	public static JpaOperation load(EntityManager em, Object key) {
		Preconditions.checkArgument(key != null && key instanceof String);
		return em.find(JpaOperation.class, key);
	}

	public static class Loader implements JpaEntityLoader<JpaOperation> {
		@Override
		public JpaOperation load(EntityManager em, Object key) {
			Preconditions.checkArgument(key != null && key instanceof String);
			
			return em.find(JpaOperation.class, key);
		}
	}
}
