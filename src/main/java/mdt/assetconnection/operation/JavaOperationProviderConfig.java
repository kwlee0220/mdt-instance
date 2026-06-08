package mdt.assetconnection.operation;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
public class JavaOperationProviderConfig {
	@JsonProperty("operationClassName") private final String m_operationClassName;
	@JsonProperty("arguments") private final Map<String,JsonNode> m_arguments;
	
	@JsonCreator
	public JavaOperationProviderConfig(@JsonProperty("operationClassName") String className,
										@Nullable @JsonProperty("arguments") Map<String,JsonNode> arguments) {
		m_operationClassName = className;
		m_arguments = arguments;
	}
	
	public String getOperationClassName() {
		return m_operationClassName;
	}
	
	public Map<String, JsonNode> getArguments() {
		return m_arguments;
	}
	
	@Override
	public String toString() {
		return "[operationClassName=" + m_operationClassName + ", arguments=" + m_arguments + "]";
	}
}
