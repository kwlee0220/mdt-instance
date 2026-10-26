package mdt.assetconnection.operation;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

import utils.UnitUtils;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter
@JsonInclude(Include.NON_NULL)
public class HttpOperationProviderConfig {
	private final String baseUrl;
	private final String endpoint;
	private final Duration pollInterval;
	private final Duration timeout;
	
	@JsonCreator
	public HttpOperationProviderConfig(@JsonProperty("baseUrl") String baseUrl,
										@JsonProperty("endpoint") String endpoint,
										@JsonProperty("pollInterval") String pollInterval,
										@JsonProperty("timeout") String timeout) {
		this.baseUrl = baseUrl;
		this.endpoint = endpoint;
		this.pollInterval = UnitUtils.parseDuration(pollInterval);
		this.timeout = UnitUtils.parseDuration(timeout);
	}
	
	@JsonProperty("pollInterval")
	public String getPollIntervalAsString() {
		return this.pollInterval.toString();
	}
	
	@JsonProperty("timeout")
	public String getTimeoutAsString() {
		return this.timeout.toString();
	}
	
	@Override
	public String toString() {
		String timeoutStr = (this.timeout != null) ? String.format(", timeout=%s", this.timeout) : "";
		return String.format("HttpOperation[server=%s, opId=%s, poll=%s%s]",
								this.baseUrl, this.endpoint, this.pollInterval, timeoutStr);
	}
}
