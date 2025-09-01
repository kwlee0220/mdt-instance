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
	private final String m_paramExpr;
	
	private String m_submodelId;
	private String m_submodelIdShort;
	private String m_elementPath;
	
	public MDTParameterLocation(String paramExpr) {
		m_paramExpr = paramExpr;
	}

	@Override
	public void activate(MDTModelLookup lookup) {
		Submodel dataSubmodel = lookup.getDataSubmodel();
		m_submodelId = dataSubmodel.getId();
		m_submodelIdShort = dataSubmodel.getIdShort();
		
		String pathPrefix = SubmodelUtils.getParameterValuePrefix(dataSubmodel);
		SubmodelElementList paramValues = SubmodelUtils.traverse(dataSubmodel, pathPrefix, SubmodelElementList.class);
		
		// parameter expression에서 parameter ID를 추출하여 해당 parameter의 인덱스를 구한다.
		m_elementPath = SubmodelUtils.resolveParameterValueElementPath(pathPrefix, m_paramExpr, paramId -> {
			return SubmodelUtils.getFieldSMCByIdValue(paramValues.getValue(),
														"ParameterID", paramId).index();
		});
	}
	
	public String getParameterName() {
		return m_paramExpr;
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
		return "param:" + m_paramExpr;
	}

	@Override
	public String toString() {
		return toStringExpr();
	}

}
