package mdt.config;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.ElementLocation;
import mdt.ElementLocations;
import mdt.persistence.MDTModelLookup;

import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public abstract class AASOperationConfig {
	private String m_operation;
	
	AASOperationConfig() { }
	
	public SubmodelElementIdentifier getOperationIdentifier() {
		ElementLocation elmLoc = ElementLocations.parseStringExpr(m_operation);
		elmLoc.activate(MDTModelLookup.getInstance());
		return elmLoc.toIdentifier();
	}

	@Getter @Setter
	@Accessors(prefix="m_")
	public static class ProgramOperationConfig extends AASOperationConfig {
		private String m_descriptorFile;
	}

	@Getter @Setter
	@Accessors(prefix="m_")
	public static class JavaOperationConfig extends AASOperationConfig {
		private String m_className;
	}

	@Getter @Setter
	@Accessors(prefix="m_")
	public static class HttpOperationConfig extends AASOperationConfig {
		private String m_endpoint;
		private String m_opId;
		private String m_pollInterval;
		private String m_timeout;
	}
}
