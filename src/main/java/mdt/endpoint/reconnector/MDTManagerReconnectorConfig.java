package mdt.endpoint.reconnector;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import utils.UnitUtils;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;
import lombok.Getter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter
@JsonInclude(Include.NON_NULL)
public class MDTManagerReconnectorConfig extends EndpointConfig<MDTManagerReconnector> {
	private String instanceId;
	private String mdtEndpoint;
	private String repositoryEndpoint;
	private Duration heartbeatInterval;
	private boolean enabled = true; 
	
	@JsonCreator
	public MDTManagerReconnectorConfig(@JsonProperty("instanceId") String instanceId,
									@JsonProperty("mdtEndpoint") String mdtEndpoint,
									@JsonProperty("repositoryEndpoint") String repositoryEndpoint,
									@JsonProperty("heartbeatInterval") String heartbeatInterval,
									@JsonProperty("enabled") boolean enabled) {
		Preconditions.checkArgument(instanceId != null, "instanceId is not set");
		Preconditions.checkArgument(mdtEndpoint != null, "mdtEndpoint is not set");
		Preconditions.checkArgument(repositoryEndpoint != null, "repositoryEndpoint is not set");
		Preconditions.checkArgument(heartbeatInterval != null, "heartbeatInterval is not set");
		
		this.instanceId = instanceId;
		this.mdtEndpoint = mdtEndpoint;
		this.repositoryEndpoint = repositoryEndpoint;
		this.heartbeatInterval = UnitUtils.parseDuration(heartbeatInterval);
		this.enabled = enabled;
	}
	
	@JsonProperty("heartbeatInterval")
	public String getHeartbeatIntervalAsString() {
		return heartbeatInterval.toString();
	}
}
