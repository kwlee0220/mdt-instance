package mdt.assetconnection.operation;

import java.util.Arrays;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;

import utils.KeyedValueList;

import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.value.FileValue;
import mdt.model.sm.value.PropertyValue.IntegerPropertyValue;
import mdt.model.sm.value.PropertyValue.StringPropertyValue;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationVariables {
	private final KeyedValueList<String, OperationVariable> m_variables;
	
	private OperationVariables(OperationVariable[] vars) {
		m_variables = KeyedValueList.from(Arrays.asList(vars), ov -> ov.getValue().getIdShort());
	}
	
	public static OperationVariables fromArray(OperationVariable[] vars) {
		return new OperationVariables(vars);
	}
	
	public boolean exists(String varName) {
		return getVariable(varName) != null;
	}
	
	public ElementValue read(String varName) {
		return ElementValues.getValue(getVariable(varName).getValue());
	}
	
	public void update(String varName, ElementValue value) {
		ElementValues.update(getVariable(varName).getValue(), value);
	}
	
	public String readString(String varName) {
		ElementValue value = read(varName);
		if ( value instanceof StringPropertyValue strVal ) {
			return strVal.get();
		}
		else {
			throw new IllegalArgumentException("OperationVariable '" + varName + "' is not Property(string) type");
		}
	}
	
	public void updateString(String varName, String value) {
	    update(varName, new StringPropertyValue(value));
	}
	
	public Integer readInt(String varName) {
		ElementValue value = read(varName);
		if ( value instanceof IntegerPropertyValue intVal ) {
			return intVal.get();
		}
		else {
			throw new IllegalArgumentException("OperationVariable '" + varName + "' is not Property(int) type");
		}
	}
	
	public void updateInt(String varName, Integer value) {
	    update(varName, new IntegerPropertyValue(value));
	}
	
	public FileValue readFile(String varName) {
		ElementValue value = read(varName);
		if ( value instanceof FileValue fileVal ) {
			return fileVal;
		}
		else {
			throw new IllegalArgumentException("OperationVariable '" + varName + "' is not File type");
		}
	}
	
	public void updateFile(String varName, FileValue value) {
	    update(varName, value);
	}
	
	private OperationVariable getVariable(String idShort) {
		OperationVariable var = m_variables.getOfKey(idShort);
		if ( var != null ) {
			return var;
		}

		throw new IllegalArgumentException("Unknown OperationVariable: " + idShort);
	}
}
