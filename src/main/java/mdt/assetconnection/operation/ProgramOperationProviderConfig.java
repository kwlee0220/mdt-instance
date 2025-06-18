package mdt.assetconnection.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({"operationDescriptorFile"})
@JsonInclude(Include.NON_NULL)
public class ProgramOperationProviderConfig {
	private final String m_opDescFile;
	
	public ProgramOperationProviderConfig(@JsonProperty("operationDescriptorFile") String opDescFile) {
		Preconditions.checkArgument(opDescFile != null, "'operationDescriptorFile' is missing");
		
		m_opDescFile = opDescFile;
	}
	
	public String getOperationDescriptorFile() {
		return m_opDescFile;
	}
}
