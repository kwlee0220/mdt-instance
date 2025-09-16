package mdt;

import java.util.Objects;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;

import com.google.common.base.Preconditions;

import utils.stream.FStream;

import mdt.persistence.MDTModelLookup;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class DefaultElementLocation implements ElementLocation {
	private final String m_submodelIdShort;
	private final String m_elementPath;
	
	private String m_submodelId;

	public DefaultElementLocation(String submodelIdShort, String elementPath) {
		m_submodelIdShort = submodelIdShort;
		m_elementPath = elementPath;
	}
	
	public static DefaultElementLocation parseString(String key) {
		String[] tokens = key.split(":");
		if ( tokens.length != 2 ) {
			throw new IllegalArgumentException("Invalid key format: " + key);
		}
		
		return new DefaultElementLocation(tokens[0], tokens[1]);
	}

	@Override
	public void activate(MDTModelLookup lookup) {
		Submodel opSubmodel
					= FStream.from(lookup.getSubmodelAll())
//							.peek(sm -> System.out.printf("%s <-> %s = %s%n", sm.getIdShort(), m_submodelIdShort, m_submodelIdShort.equals(sm.getIdShort())))
							.findFirst(sm -> m_submodelIdShort.equals(sm.getIdShort()))
							.getOrThrow(() -> new IllegalArgumentException("Submodel not found: "
																			+ m_submodelIdShort));
		m_submodelId = opSubmodel.getId();
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

	@Override
	public String getElementPath() {
		return m_elementPath;
	}

	@Override
	public String toStringExpr() {
		return String.format("%s:%s", m_submodelIdShort, m_elementPath);
	}
	
	@Override
	public String toString() {
		return toStringExpr();
	}
	
	@Override
	public boolean equals(Object obj) {
        if ( this == obj ) {
            return true;
        }
        if ( obj == null || !(obj instanceof DefaultElementLocation) ) {
            return false;
        }

        DefaultElementLocation other = (DefaultElementLocation)obj;
        return m_submodelIdShort.equals(other.m_submodelIdShort)
                && m_elementPath.equals(other.m_elementPath);
	}
	
	@Override
	public int hashCode() {
		return Objects.hash(m_submodelIdShort, m_elementPath);
	}
}
