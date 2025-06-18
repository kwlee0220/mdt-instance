package mdt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "name", "element" })
@JsonInclude(Include.NON_NULL)
public class ElementColumnConfig {
	private final String m_name;
	private final ElementLocation m_elementLoc;
	
	@JsonCreator
	public ElementColumnConfig(@JsonProperty("name") String name,
								@JsonProperty("element") String elementLocStr) {
		m_name = name;
		m_elementLoc = ElementLocations.parseStringExpr(elementLocStr);
	}
	
	public String getName() {
		return m_name;
	}
	
	public ElementLocation getElementLocation() {
		return m_elementLoc;
	}
	
	@JsonProperty("element")
	public String getElementLocationExpr() {
		return m_elementLoc.toStringExpr();
	}
	
	@Override
	public String toString() {
		return String.format("%s: %s", this.m_name, m_elementLoc.toStringExpr());
	}
}
