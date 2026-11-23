package mdt.assetconnection.operation;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

import utils.Durations;
import utils.func.Nullables;


/**
 * {@link HttpOperationProvider}의 설정({@code operations.https}의 항목).
 * <ul>
 *   <li>{@code baseUrl}: 연산 서버의 기본 URL(예: {@code http://localhost:12987}).</li>
 *   <li>{@code endpoint}: 연산 경로(예: {@code /api/v1/operations/AddAndSleep}). 호출 URL은 {@code baseUrl + endpoint}다.</li>
 *   <li>{@code pollInterval}: 세션 상태 조회 주기. 생략하면 3초.</li>
 *   <li>{@code timeout}: 완료를 기다리는 제한 시간. 생략하면 제한 없음.</li>
 * </ul>
 * 시간 값은 {@code Durations.parse} 형식({@code "3s"}, {@code "PT3S"} 등)이다.
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
		this.pollInterval = Nullables.map(pollInterval, Durations::parse);
		this.timeout = Nullables.map(timeout, Durations::parse);
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
		return String.format("HttpOperation[server=%s, endpoint=%s, poll=%s%s]",
								this.baseUrl, this.endpoint, this.pollInterval, timeoutStr);
	}
}
