package mdt.persistence.asset.robot;

import java.time.Instant;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;



/**
 * 로봇 트윈 REST API로 조회한 상태 변수 하나의 값과 메타 정보.
 * <p>
 * {@code GET /api/v1/robot_twins/{twin}/variables?names=...} 응답의
 * {@code values} 항목 하나에 해당한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RobotTwinVariable {
	private String m_name;
	private Quality m_quality = Quality.UNKNOWN;
	private @Nullable Stamp m_stamp;
	private @Nullable Instant m_receivedAt;
	private @Nullable Long m_ageMillis;
	private int m_schemaVersion;
	private @Nullable JsonNode m_value;
	private @Nullable String m_reason;
	private @Nullable String m_error;

	/**
	 * 변수 이름을 반환한다.
	 *
	 * @return 변수 이름.
	 */
	@JsonProperty("name")
	public String getName() {
		return m_name;
	}

	@JsonProperty("name")
	public void setName(String name) {
		m_name = name;
	}

	/**
	 * 값의 신선도를 반환한다.
	 * <p>
	 * 값을 사용하기 전에 반드시 확인해야 한다.
	 *
	 * @return 값의 신선도.
	 */
	@JsonProperty("quality")
	public Quality getQuality() {
		return m_quality;
	}

	@JsonProperty("quality")
	public void setQuality(Quality quality) {
		m_quality = quality;
	}

	/**
	 * 로봇이 값을 찍은 시각을 반환한다.
	 *
	 * @return 값의 생성 시각. 아직 수신된 값이 없는 경우는 {@code null}.
	 */
	@JsonProperty("stamp")
	public @Nullable Stamp getStamp() {
		return m_stamp;
	}

	@JsonProperty("stamp")
	public void setStamp(@Nullable Stamp stamp) {
		m_stamp = stamp;
	}

	/**
	 * 로봇 트윈이 값을 수신한 시각을 반환한다.
	 *
	 * @return 값의 수신 시각. 아직 수신된 값이 없는 경우는 {@code null}.
	 */
	@JsonProperty("received_at")
	public @Nullable Instant getReceivedAt() {
		return m_receivedAt;
	}

	@JsonProperty("received_at")
	public void setReceivedAt(@Nullable Instant receivedAt) {
		m_receivedAt = receivedAt;
	}

	/**
	 * 값의 나이를 반환한다.
	 *
	 * @return 값의 나이 (milliseconds). 아직 수신된 값이 없는 경우는 {@code null}.
	 */
	@JsonProperty("age_ms")
	public @Nullable Long getAgeMillis() {
		return m_ageMillis;
	}

	@JsonProperty("age_ms")
	public void setAgeMillis(@Nullable Long ageMillis) {
		m_ageMillis = ageMillis;
	}

	/**
	 * 값 스키마의 버전을 반환한다.
	 *
	 * @return 스키마 버전.
	 */
	@JsonProperty("schema_version")
	public int getSchemaVersion() {
		return m_schemaVersion;
	}

	@JsonProperty("schema_version")
	public void setSchemaVersion(int schemaVersion) {
		m_schemaVersion = schemaVersion;
	}

	/**
	 * ROS 메시지를 변환한 JSON 값을 반환한다.
	 *
	 * @return 변수 값. 아직 수신된 값이 없는 경우는 {@code null}.
	 */
	@JsonProperty("value")
	public @Nullable JsonNode getValue() {
		return m_value;
	}

	@JsonProperty("value")
	public void setValue(@Nullable JsonNode value) {
		m_value = value;
	}

	/**
	 * 값을 제공하는 소스가 없는 사유를 반환한다.
	 *
	 * @return {@link Quality#SOURCE_UNAVAILABLE}인 경우의 사유, 그 외의 경우는 {@code null}.
	 */
	@JsonProperty("reason")
	public @Nullable String getReason() {
		return m_reason;
	}

	@JsonProperty("reason")
	public void setReason(@Nullable String reason) {
		m_reason = reason;
	}

	/**
	 * 값 변환에 실패한 원인을 반환한다.
	 *
	 * @return {@link Quality#ERROR}인 경우의 오류 메시지, 그 외의 경우는 {@code null}.
	 */
	@JsonProperty("error")
	public @Nullable String getError() {
		return m_error;
	}

	@JsonProperty("error")
	public void setError(@Nullable String error) {
		m_error = error;
	}

	/**
	 * 값이 신선한지 여부를 반환한다.
	 *
	 * @return {@link Quality#OK}인 경우는 {@code true}, 그렇지 않은 경우는 {@code false}.
	 */
	public boolean isOk() {
		return m_quality == Quality.OK;
	}

	@Override
	public String toString() {
		return String.format("%s(%s%s)", m_name, m_quality,
							(m_ageMillis != null) ? ", age=" + m_ageMillis + "ms" : "");
	}

	/**
	 * 상태 변수 값의 신선도.
	 */
	public static enum Quality {
		/** 값이 신선하다. */
		OK("OK"),
		/** 값은 있으나 임계값보다 오래됐다. */
		STALE("STALE"),
		/** 아직 한 번도 값을 수신하지 못했다. */
		NO_DATA("NO_DATA"),
		/** 값을 제공하는 소스 자체가 없다. */
		SOURCE_UNAVAILABLE("SOURCE_UNAVAILABLE"),
		/** 값을 수신했으나 변환에 실패했다. */
		ERROR("ERROR"),
		/** 로봇 트윈이 알 수 없는 quality를 보낸 경우. */
		UNKNOWN("UNKNOWN");

		private final String m_code;

		private Quality(String code) {
			m_code = code;
		}

		@JsonValue
		public String getCode() {
			return m_code;
		}

		/**
		 * 주어진 문자열에 해당하는 {@link Quality}를 반환한다.
		 * <p>
		 * 로봇 트윈이 향후 새로운 quality를 추가하더라도 조회가 실패하지 않도록,
		 * 알 수 없는 문자열은 {@link #UNKNOWN}으로 처리한다.
		 *
		 * @param code quality 문자열.
		 * @return 해당 {@link Quality}. 알 수 없는 문자열인 경우는 {@link #UNKNOWN}.
		 */
		@JsonCreator
		public static Quality fromCode(String code) {
			for ( Quality quality: values() ) {
				if ( quality.m_code.equalsIgnoreCase(code) ) {
					return quality;
				}
			}
			return UNKNOWN;
		}
	}
}
