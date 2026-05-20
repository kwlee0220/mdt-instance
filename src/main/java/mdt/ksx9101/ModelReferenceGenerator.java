package mdt.ksx9101;

import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;

import utils.StrSubstitutor;

import mdt.model.ReferenceUtils;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ModelReferenceGenerator {
	private final String m_refTemplate;
	
	public ModelReferenceGenerator(String refTemplate) {
		m_refTemplate = refTemplate;
	}
	
	public Reference newReference(KeyTypes type, String src) {
		String modelId = StrSubstitutor.with("id", src)
										.failOnUndefinedVariable(false)
										.replace(m_refTemplate);
		switch ( type ) {
			case ASSET_ADMINISTRATION_SHELL:
				return ReferenceUtils.toAASReference(modelId);
			case SUBMODEL:
				return ReferenceUtils.toSubmodelReference(modelId);
			default:
				throw new IllegalArgumentException("Unsupported Reference KeyTypes: " + type);
		}
	}
}
