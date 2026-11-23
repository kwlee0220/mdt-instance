package mdt.endpoint.reconnector;

import java.time.Duration;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;

import utils.Durations;


/**
 * {@link MDTManagerReconnector}의 FA³ST endpoint 설정.
 * <p>
 * {@code operationControlEndpoint}는 AAS 비동기 연산 취소 엔드포인트의 주소(예: {@code https://host:19081})다.
 * 값이 있으면 등록 요청에 함께 보낸다. 취소 엔드포인트를 열지 않으면 {@code null}이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
public class MDTManagerReconnectorConfig extends EndpointConfig<MDTManagerReconnector> {
	private String m_instanceId;
	private String m_mdtUrl;
	private String m_repositoryEndpoint;
	private Duration m_heartbeatInterval;
	@Nullable private String m_operationControlEndpoint;
	private boolean m_enabled = true; 
	
	@JsonCreator
	public MDTManagerReconnectorConfig(@JsonProperty("instanceId") String instanceId,
									@JsonProperty("mdtUrl") String mdtUrl,
									@JsonProperty("repositoryEndpoint") String repositoryEndpoint,
									@JsonProperty("heartbeatInterval") String heartbeatInterval,
									@JsonProperty("operationControlEndpoint") @Nullable String operationControlEndpoint,
									@JsonProperty("enabled") boolean enabled) {
		Preconditions.checkArgument(instanceId != null, "instanceId is not set");
		Preconditions.checkArgument(mdtUrl != null, "mdtUrl is not set");
		Preconditions.checkArgument(repositoryEndpoint != null, "repositoryEndpoint is not set");
		Preconditions.checkArgument(heartbeatInterval != null, "heartbeatInterval is not set");
		
		this.m_instanceId = instanceId;
		this.m_mdtUrl = mdtUrl;
		this.m_repositoryEndpoint = repositoryEndpoint;
		this.m_heartbeatInterval = Durations.parse(heartbeatInterval);
		this.m_operationControlEndpoint = operationControlEndpoint;
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
	
	/**
	 * AAS 비동기 연산 취소 엔드포인트의 주소를 반환한다.
	 *
	 * @return	주소. 취소 엔드포인트를 열지 않으면 {@code null}.
	 */
	public @Nullable String getOperationControlEndpoint() {
		return m_operationControlEndpoint;
	}
	
	public boolean isEnabled() {
		return m_enabled;
	}
}
