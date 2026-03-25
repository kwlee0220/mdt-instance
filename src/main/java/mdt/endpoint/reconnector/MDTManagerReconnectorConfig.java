package mdt.endpoint.reconnector;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;

import utils.UnitUtils;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
public class MDTManagerReconnectorConfig extends EndpointConfig<MDTManagerReconnector> {
	private String m_instanceId;
	private String m_mdtUrl;
	private String m_repositoryEndpoint;
	private Duration m_heartbeatInterval;
	private boolean m_enabled = true; 
	
	@JsonCreator
	public MDTManagerReconnectorConfig(@JsonProperty("instanceId") String instanceId,
									@JsonProperty("mdtUrl") String mdtUrl,
									@JsonProperty("repositoryEndpoint") String repositoryEndpoint,
									@JsonProperty("heartbeatInterval") String heartbeatInterval,
									@JsonProperty("enabled") boolean enabled) {
		Preconditions.checkArgument(instanceId != null, "instanceId is not set");
		Preconditions.checkArgument(mdtUrl != null, "mdtUrl is not set");
		Preconditions.checkArgument(repositoryEndpoint != null, "repositoryEndpoint is not set");
		Preconditions.checkArgument(heartbeatInterval != null, "heartbeatInterval is not set");
		
		this.m_instanceId = instanceId;
		this.m_mdtUrl = mdtUrl;
		this.m_repositoryEndpoint = repositoryEndpoint;
		this.m_heartbeatInterval = UnitUtils.parseDuration(heartbeatInterval);
		this.m_enabled = enabled;
	}
	
	public String getInstanceId() {
		return m_instanceId;
	}
	
	public String getMdtUrl() {
		return m_mdtUrl;
	}
	
	public String getRepositoryEndpoint() {
		return m_repositoryEndpoint;
	}
	
	public Duration getHeartbeatInterval() {
		return m_heartbeatInterval;
	}
	
	@JsonProperty("heartbeatInterval")
	public String getHeartbeatIntervalAsString() {
		return m_heartbeatInterval.toString();
	}
	
	public boolean isEnabled() {
		return m_enabled;
	}
}
