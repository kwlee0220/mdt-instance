package mdt;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;

import com.google.common.base.Preconditions;

import mdt.model.sm.SubmodelUtils;
import mdt.persistence.MDTModelLookup;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTParameterLocation implements ElementLocation {
	private final String m_parameterName;
	private final String m_subPath;
	
	private String m_submodelId;
	private String m_submodelIdShort;
	private String m_elementPath;
	
	public MDTParameterLocation(String parameterName, String subPath) {
		m_parameterName = parameterName;
		m_subPath = subPath;
	}

	@Override
	public void activate(MDTModelLookup lookup) {
		Submodel dataSubmodel = lookup.getDataSubmodel();
		m_submodelId = dataSubmodel.getId();
		m_submodelIdShort = dataSubmodel.getIdShort();
		
		String pathPrefix = SubmodelUtils.getParameterValuePrefix(dataSubmodel);
		SubmodelElementList paramValues = SubmodelUtils.traverse(dataSubmodel, pathPrefix, SubmodelElementList.class);
		int paramIdx = SubmodelUtils.getFieldSMCByIdValue(paramValues.getValue(),
															"ParameterID", m_parameterName).index();
		m_elementPath = String.format("%s[%d]", pathPrefix, paramIdx);
		if ( m_subPath != null && m_subPath.length() > 0 ) {
			m_elementPath = m_elementPath + "." + m_subPath;
		}
	}
	
	public String getParameterName() {
		return m_parameterName;
	}

	@Override
	public String getSubmodelId() {
		Preconditions.checkState(m_submodelId != null, "not activated");
		
		return m_submodelId;
	}

	@Override
	public String getSubmodelIdShort() {
		Preconditions.checkState(m_submodelIdShort != null, "not activated");
		
		return m_submodelIdShort;
	}

	@Override
	public String getElementPath() {
		Preconditions.checkState(m_elementPath != null, "not activated");
		
		return m_elementPath;
	}

	@Override
	public String toStringExpr() {
		String str = "param:" + m_parameterName;
		if ( m_subPath != null && m_subPath.length() > 0 ) {
			return str + ":" + m_subPath;
		}
		else {
			return str;
		}
	}

	@Override
	public String toString() {
		return toStringExpr();
	}

}
