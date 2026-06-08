package mdt.config;

import java.util.Collections;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;

import utils.func.FOption;

import mdt.ElementLocation;
import mdt.ElementLocations;
import mdt.persistence.MDTModelLookup;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AASOperationConfig {
	private String m_operation;
	
	AASOperationConfig() { }
	
	public String getOperation() {
		return m_operation;
	}
	
	public void setOperation(String operation) {
		m_operation = operation;
	}
	
	public SubmodelElementIdentifier getOperationIdentifier() {
		ElementLocation elmLoc = ElementLocations.parseStringExpr(m_operation);
		elmLoc.activate(MDTModelLookup.getInstance());
		return elmLoc.toIdentifier();
	}

	public static class ProgramOperationConfig extends AASOperationConfig {
		private String m_descriptorFile;
		
		public String getDescriptorFile() {
			return m_descriptorFile;
		}
		
		public void setDescriptorFile(String descriptorFile) {
			m_descriptorFile = descriptorFile;
		}
	}

	public static class JavaOperationConfig extends AASOperationConfig {
		private String m_className;
		private Map<String,JsonNode> m_arguments;
		
		public String getClassName() {
			return m_className;
		}
		
		public void setClassName(String className) {
			m_className = className;
		}
		
		public Map<String, JsonNode> getArguments() {
			return FOption.getOrElse(m_arguments, Collections.emptyMap());
		}
		
		public void setArguments(Map<String, JsonNode> arguments) {
			m_arguments = arguments;
		}
	}

	public static class HttpOperationConfig extends AASOperationConfig {
		private String m_endpoint;
		private String m_opId;
		private String m_pollInterval;
		private String m_timeout;
		
		public String getEndpoint() {
			return m_endpoint;
		}
		
		public void setEndpoint(String endpoint) {
			m_endpoint = endpoint;
		}
		
		public String getOpId() {
			return m_opId;
		}
		
		public void setOpId(String opId) {
			m_opId = opId;
		}
		
		public String getPollInterval() {
			return m_pollInterval;
		}
		
		public void setPollInterval(String pollInterval) {
			m_pollInterval = pollInterval;
		}
		
		public String getTimeout() {
			return m_timeout;
		}
		
		public void setTimeout(String timeout) {
			m_timeout = timeout;
		}
	}
}
