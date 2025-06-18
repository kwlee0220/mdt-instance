package mdt.persistence.opcua;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import lombok.Getter;
import lombok.experimental.Accessors;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter
@Accessors(prefix="m_")
@JsonIncludeProperties({ "serverEndpoint" })
public class OpcUaServerConfig {
	private final String m_serverEndpoint;
	
	public OpcUaServerConfig(@Nullable @JsonProperty("serverEndpoint") String serverEndpoint) {
		Preconditions.checkArgument(serverEndpoint != null, "serverEndpoint must be specified");
		
		m_serverEndpoint = serverEndpoint;
	}
	
	@Override
	public String toString() {
		return String.format("%s: endpoint=%s",
							getClass().getSimpleName(), m_serverEndpoint);
	}
}
