package mdt.assetconnection.operation;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import utils.UnitUtils;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
public class ScriptOperationProviderConfig {
	@JsonProperty("scriptFile") private final String m_scriptFile;
	@JsonProperty("timeout") private final Duration m_timeout;
	
	public ScriptOperationProviderConfig(@JsonProperty("scriptFile") String scriptFile,
										 @JsonProperty("timeout") String timeout) {
		Preconditions.checkArgument(scriptFile != null, "'scriptFile' is missing");
		
		m_scriptFile = scriptFile;
		m_timeout = UnitUtils.parseDuration(timeout);
	}
	
	public String getScriptFile() {
		return m_scriptFile;
	}
	
	public Duration getTimeout() {
		return m_timeout;
	}
	
	@JsonProperty("timeout")
	public String getTimeoutString() {
		return m_timeout.toString();
	}
	
	@Override
	public String toString() {
		return "ScriptOperationProviderConfig{scriptFile=" + m_scriptFile + ", timeout=" + m_timeout + "}";
	}
}
