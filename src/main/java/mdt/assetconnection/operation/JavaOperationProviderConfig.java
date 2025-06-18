package mdt.assetconnection.operation;

import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
public class JavaOperationProviderConfig {
	@JsonProperty("operationClassName") private final String m_operationClassName;
	@JsonProperty("arguments") private final Map<String,Object> m_arguments;
	
	@JsonCreator
	public JavaOperationProviderConfig(@JsonProperty("operationClassName") String className,
										@Nullable @JsonProperty("arguments") Map<String,Object> arguments) {
		m_operationClassName = className;
		m_arguments = arguments;
	}
	
	public String getOperationClassName() {
		return m_operationClassName;
	}
	
	public Map<String, Object> getArguments() {
		return m_arguments;
	}
}
