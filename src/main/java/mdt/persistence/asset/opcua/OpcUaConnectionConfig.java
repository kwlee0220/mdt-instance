package mdt.persistence.asset.opcua;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import lombok.Getter;
import lombok.experimental.Accessors;

import javax.annotation.Nullable;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter
@Accessors(prefix="m_")
@JsonIncludeProperties({ "serverEndpoint" })
public class OpcUaConnectionConfig {
	private final String m_serverEndpoint;
	
	public OpcUaConnectionConfig(@Nullable @JsonProperty("serverEndpoint") String serverEndpoint) {
		Preconditions.checkArgument(serverEndpoint != null, "serverEndpoint must be specified");
		
		m_serverEndpoint = serverEndpoint;
	}
	
	@Override
	public String toString() {
		return String.format("%s: endpoint=%s",
							getClass().getSimpleName(), m_serverEndpoint);
	}
}
