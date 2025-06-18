package mdt;

import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;

import com.google.common.base.Preconditions;

import utils.Utilities;
import utils.stream.FStream;

import mdt.model.ReferenceUtils;
import mdt.model.sm.ai.AI;
import mdt.model.sm.ref.MDTArgumentKind;
import mdt.model.sm.simulation.Simulation;
import mdt.persistence.MDTModelLookup;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTOperationArgumentLocation implements ElementLocation {
	private final String m_submodelIdShort;
	private final MDTArgumentKind m_kind;
	private final String m_argName;

	private String m_submodelId;
	private String m_elementPath;

	public MDTOperationArgumentLocation(String submodelIdShort, MDTArgumentKind kind, String argName) {
		m_submodelIdShort = submodelIdShort;
		m_kind = kind;
		m_argName = argName;
	}

	@Override
	public void activate(MDTModelLookup lookup) {
		Submodel opSubmodel = FStream.from(lookup.getSubmodelAll())
									.findFirst(sm -> m_submodelIdShort.equals(sm.getIdShort()))
									.getOrThrow(() -> new IllegalArgumentException("Submodel not found: "
																					+ m_submodelIdShort));
		m_submodelId = opSubmodel.getId();
		String semanticId = ReferenceUtils.getSemanticIdStringOrNull(opSubmodel.getSemanticId());

		String kindStr = switch ( m_kind ) {
			case INPUT -> "Input";
			case OUTPUT -> "Output";
			default -> throw new IllegalArgumentException("Invalid OperationArgument's kind: " + m_kind);
		};
		
		String pathPrefix;
		if ( AI.SEMANTIC_ID.equals(semanticId) ) {
			m_elementPath = Utilities.substributeString("AIInfo.${kind}s.${kind}Value", Map.of("kind", kindStr));
		}
		else if ( Simulation.SEMANTIC_ID.equals(semanticId) ) {
			m_elementPath = Utilities.substributeString("SimulationInfo.${kind}s.${kind}Value", Map.of("kind", kindStr));
		}
		else {
			throw new IllegalArgumentException("Invalid submodel semanticId: " + semanticId);
		}
	}

	@Override
	public String getSubmodelId() {
		Preconditions.checkState(m_submodelId != null, "not activated");
		
		return m_submodelId;
	}

	@Override
	public String getSubmodelIdShort() {
		return m_submodelIdShort;
	}
	
	public MDTArgumentKind getKind() {
		return m_kind;
	}

	public String getArgumentName() {
		return m_argName;
	}

	@Override
	public String getElementPath() {
		Preconditions.checkState(m_elementPath != null, "not activated");
		
		return m_elementPath;
	}

	@Override
	public String toStringExpr() {
		return String.format("oparg:%s:%s:%s", m_submodelIdShort, m_kind, m_argName);
	}
	
	@Override
	public String toString() {
		return toStringExpr();
	}
}
