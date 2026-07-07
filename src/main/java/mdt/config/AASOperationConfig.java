package mdt.config;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;

import utils.UnitUtils;
import utils.func.FOption;

import mdt.ElementLocation;
import mdt.ElementLocations;
import mdt.persistence.MDTModelLookup;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public sealed class AASOperationConfig
		permits AASOperationConfig.ProgramConfig, AASOperationConfig.JavaConfig,
				AASOperationConfig.HttpConfig, AASOperationConfig.ScriptConfig {
	AASOperationConfig() { }
	
	public SubmodelElementIdentifier getOperationIdentifier(String opRefStr) {
		ElementLocation elmLoc = ElementLocations.parseStringExpr(opRefStr);
		elmLoc.activate(MDTModelLookup.getInstance());
		return elmLoc.toIdentifier();
	}

	public static final class ProgramConfig extends AASOperationConfig {
		private String m_descriptorFile;
		
		public String getDescriptorFile() {
			return m_descriptorFile;
		}
		
		public void setDescriptorFile(String descriptorFile) {
			m_descriptorFile = descriptorFile;
		}
	}

	public static final class JavaConfig extends AASOperationConfig {
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

	public static final class ScriptConfig extends AASOperationConfig {
		@JsonProperty("scriptFile") private String m_scriptFile;
		@JsonProperty("timeout") private Duration m_timeout;
		
		public String getScriptFile() {
			return m_scriptFile;
		}
		
		public void setScriptFile(String scriptFile) {
			m_scriptFile = scriptFile;
		}
		
		public Duration getTimeout() {
			return m_timeout;
		}

		@JsonProperty("timeout")
		public String getTimeoutAsString() {
			return m_timeout.toString();
		}
		
		@JsonProperty("timeout")
		public void setTimeoutString(String timeout) {
			m_timeout = UnitUtils.parseDuration(timeout);
		}
	}

	public static final class HttpConfig extends AASOperationConfig {
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
