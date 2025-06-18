package mdt.persistence.asset.jdbc;

import java.io.IOException;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import com.fasterxml.jackson.databind.JsonNode;

import utils.InternalException;

import mdt.aas.DataType;
import mdt.aas.DataTypes;
import mdt.model.MDTModelSerDe;
import mdt.model.sm.value.ElementValues;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SubmodelElementHandler {
	@SuppressWarnings("rawtypes")
	private final DataType m_type;
	
	public SubmodelElementHandler(SubmodelElement sme) {
		if ( sme instanceof Property prop ) {
			m_type = DataTypes.fromAas4jDatatype(prop.getValueType());
		}
		else {
			m_type = null;
		}
	}
	
	@SuppressWarnings("unchecked")
	public Object toJdbcObject(SubmodelElement element) {
		if ( m_type != null ) {
			Object jvObj = m_type.parseValueString(((Property)element).getValue());
			return m_type.toJdbcObject(jvObj);
		}
		else {
			return MDTModelSerDe.toJsonString(ElementValues.getValue(element));
		}
	}
	
	public void update(SubmodelElement element, Object value) {
		if ( m_type != null ) {
			((Property)element).setValue(m_type.toValueString(value));
		}
		else {
			try {
				updateWithValueJsonString(element, (String)value);
			}
			catch ( IOException e ) {
				String msg = String.format("Failed to update %s with value=%s", element, value);
				throw new InternalException(msg, e);
			}
		}
	}

	public void updateWithValueJsonNode(SubmodelElement sme, JsonNode valueNode) throws IOException {
		ElementValues.update(sme, valueNode);
	}

	public void updateWithValueJsonString(SubmodelElement sme, String valueJsonString) throws IOException {
		updateWithValueJsonNode(sme, MDTModelSerDe.readJsonNode(valueJsonString));
	}
	
	public void updateWithJdbcObject(SubmodelElement element, Object jdbcValue) {
		if ( m_type != null ) {
			update(element, m_type.fromJdbcObject(jdbcValue));
		}
		else {
			update(element, jdbcValue);
		}
	}
}
