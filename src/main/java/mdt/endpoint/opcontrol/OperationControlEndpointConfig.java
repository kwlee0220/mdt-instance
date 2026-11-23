package mdt.endpoint.opcontrol;

import java.time.Duration;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import utils.Preconditions;
import utils.Durations;

import de.fraunhofer.iosb.ilt.faaast.service.config.CertificateConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;

import mdt.assetconnection.operation.OperationExecutionRegistry;


/**
 * {@link OperationControlEndpoint}의 FA³ST endpoint 설정.
 * <p>
 * MDT 인스턴스 설정의 {@code serviceEndpoints.operationControl} 항목({@link MDTConfig})에 인스턴스의 인증서
 * 설정을 더해 {@code MDTInstanceMain}이 {@link #from}으로 만든다. 인증서 설정이 있으면 HTTPS로, 없으면 HTTP로 연다.
 * 인증서 설정에는 암호가 들어 있으므로 직렬화하지 않는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "port", "cancelWait", "retainTimeout" })
public class OperationControlEndpointConfig extends EndpointConfig<OperationControlEndpoint> {
	private final int m_port;
	private final Duration m_cancelWait;
	private final Duration m_retainTimeout;
	@Nullable private final CertificateConfig m_certificate;

	/**
	 * 인스턴스 설정의 {@code serviceEndpoints.operationControl} 항목으로 설정을 만든다.
	 *
	 * @param conf			인스턴스 설정 항목.
	 * @param certificate	HTTPS에 쓸 인증서 설정. {@code null}이면 HTTP로 연다.
	 * @return	엔드포인트 설정.
	 */
	public static OperationControlEndpointConfig from(MDTConfig conf, @Nullable CertificateConfig certificate) {
		Preconditions.checkNotNullArgument(conf, "operationControl config is null");
		return new OperationControlEndpointConfig(conf.getPort(), conf.getCancelWait(), conf.getRetainTimeout(),
													certificate);
	}

	/**
	 * 설정을 만든다.
	 *
	 * @param port			취소 요청을 받을 포트. {@code 0}이면 비어 있는 포트를 고른다.
	 * @param cancelWait	취소 요청 후 실행이 끝나기를 기다리는 시간.
	 * @param retainTimeout	끝난 실행을 보존하는 시간.
	 * @param certificate	HTTPS에 쓸 인증서 설정. {@code null}이면 HTTP로 연다.
	 */
	public OperationControlEndpointConfig(int port, Duration cancelWait, Duration retainTimeout,
											@Nullable CertificateConfig certificate) {
		Preconditions.checkArgument(port >= 0 && port <= 65535, "invalid port: %s", port);
		Preconditions.checkArgument(cancelWait != null && !cancelWait.isNegative(), "invalid cancelWait: %s", cancelWait);
		Preconditions.checkArgument(retainTimeout != null && !retainTimeout.isNegative(),
									"invalid retainTimeout: %s", retainTimeout);

		m_port = port;
		m_cancelWait = cancelWait;
		m_retainTimeout = retainTimeout;
		m_certificate = certificate;
	}

	public int getPort() {
		return m_port;
	}

	@JsonIgnore
	public Duration getCancelWait() {
		return m_cancelWait;
	}

	@JsonProperty("cancelWait")
	public String getCancelWaitAsString() {
		return m_cancelWait.toString();
	}

	@JsonIgnore
	public Duration getRetainTimeout() {
		return m_retainTimeout;
	}

	@JsonProperty("retainTimeout")
	public String getRetainTimeoutAsString() {
		return m_retainTimeout.toString();
	}

	@JsonIgnore
	public @Nullable CertificateConfig getCertificate() {
		return m_certificate;
	}

	@Override
	public boolean equals(Object obj) {
		if ( this == obj ) {
			return true;
		}
		if ( obj == null || getClass() != obj.getClass() ) {
			return false;
		}
		OperationControlEndpointConfig other = (OperationControlEndpointConfig)obj;
		return super.equals(other) && m_port == other.m_port && m_cancelWait.equals(other.m_cancelWait)
				&& m_retainTimeout.equals(other.m_retainTimeout) && Objects.equals(m_certificate, other.m_certificate);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), m_port, m_cancelWait, m_retainTimeout, m_certificate);
	}

	@Override
	public String toString() {
		return String.format("OperationControlEndpointConfig[port=%d, cancelWait=%s, retainTimeout=%s, https=%s]",
								m_port, m_cancelWait, m_retainTimeout, m_certificate != null);
	}

	/**
	 * MDT 인스턴스 설정의 {@code serviceEndpoints.operationControl} 항목.
	 * <pre>{@code
	 * "operationControl": { "port": 19081, "cancelWait": "2s", "retainTimeout": "30s" }
	 * }</pre>
	 * <ul>
	 *   <li>{@code port} (필수, 1~65535): 취소 요청을 받을 포트. 인스턴스는 이 포트로 만든 주소를 MDT Manager에
	 *       알리므로 미리 정한 포트여야 한다.</li>
	 *   <li>{@code cancelWait} (기본 {@code 2s}): 취소 요청 후 실행이 끝나기를 기다리는 시간. 이 안에 끝나면
	 *       {@code 200}, 아니면 {@code 202}로 답한다. mdtpy의 취소 요청 시간 제한(3초)보다 짧아야 한다.</li>
	 *   <li>{@code retainTimeout} (기본 {@code 30s}): 끝난 실행을 보존하는 시간. 이 동안의 취소 요청에는
	 *       {@code 409}, 지난 뒤에는 {@code 404}로 답한다.</li>
	 * </ul>
	 * 시간 값은 {@code Durations.parse} 형식({@code "2s"}, {@code "PT2S"} 등)이다.
	 */
	@JsonIncludeProperties({ "port", "cancelWait", "retainTimeout" })
	public static class MDTConfig {
		public static final Duration DEFAULT_CANCEL_WAIT = Duration.ofSeconds(2);

		private Integer m_port;
		private Duration m_cancelWait = DEFAULT_CANCEL_WAIT;
		private Duration m_retainTimeout = OperationExecutionRegistry.DEFAULT_RETAIN_TIMEOUT;

		public int getPort() {
			Preconditions.checkState(m_port != null, "'port' is not set in serviceEndpoints.operationControl");
			return m_port;
		}

		@JsonProperty("port")
		public void setPort(Integer port) {
			Preconditions.checkArgument(port != null && port >= 1 && port <= 65535,
										"invalid operationControl port: %s", port);
			m_port = port;
		}

		@JsonIgnore
		public Duration getCancelWait() {
			return m_cancelWait;
		}

		@JsonProperty("cancelWait")
		public void setCancelWait(String cancelWait) {
			m_cancelWait = parseNonNegative("cancelWait", cancelWait);
		}

		@JsonIgnore
		public Duration getRetainTimeout() {
			return m_retainTimeout;
		}

		@JsonProperty("retainTimeout")
		public void setRetainTimeout(String retainTimeout) {
			m_retainTimeout = parseNonNegative("retainTimeout", retainTimeout);
		}

		private static Duration parseNonNegative(String name, String value) {
			Preconditions.checkArgument(value != null, "'%s' is null", name);
			Duration duration = Durations.parse(value);
			Preconditions.checkArgument(!duration.isNegative(), "'%s' is negative: %s", name, value);
			return duration;
		}

		@Override
		public boolean equals(Object obj) {
			if ( this == obj ) {
				return true;
			}
			if ( obj == null || getClass() != obj.getClass() ) {
				return false;
			}
			MDTConfig other = (MDTConfig)obj;
			return Objects.equals(m_port, other.m_port) && m_cancelWait.equals(other.m_cancelWait)
					&& m_retainTimeout.equals(other.m_retainTimeout);
		}

		@Override
		public int hashCode() {
			return Objects.hash(m_port, m_cancelWait, m_retainTimeout);
		}

		@Override
		public String toString() {
			return String.format("port=%s, cancelWait=%s, retainTimeout=%s", m_port, m_cancelWait, m_retainTimeout);
		}
	}
}
