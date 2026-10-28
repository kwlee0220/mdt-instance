package mdt.endpoint.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.io.JsonStringEncoder;


/**
 * MQTT 메시지 payload의 형식.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public enum PayloadFormat {
	/**
	 * AAS value-only JSON(기본값). 숫자 Property는 {@code 23.5}, 문자열 Property는 {@code "idle"},
	 * Collection은 {@code {"Joint1": 0.25}}처럼 대상 요소 타입에 맞는 JSON이어야 한다.
	 */
	JSON("json"),
	/**
	 * payload 전체를 Property 값으로 쓰는 일반 텍스트. {@code idle}처럼 따옴표 없이 보내면 된다.
	 * Property 대상에만 쓸 수 있으며, 값은 Property의 데이터 타입에 맞게 해석된다(예: 숫자 Property에 {@code 23.5}).
	 */
	TEXT("text");

	private final String m_jsonValue;

	private PayloadFormat(String jsonValue) {
		m_jsonValue = jsonValue;
	}

	@JsonValue
	public String toJsonValue() {
		return m_jsonValue;
	}

	/**
	 * 설정 문자열을 해석한다. 대소문자를 구분하지 않는다.
	 *
	 * @param str	{@code json} 또는 {@code text}.
	 * @return	payload 형식.
	 * @throws IllegalArgumentException	알 수 없는 값인 경우.
	 */
	@JsonCreator
	public static PayloadFormat fromString(String str) {
		for ( PayloadFormat format: values() ) {
			if ( format.m_jsonValue.equalsIgnoreCase(str) ) {
				return format;
			}
		}
		throw new IllegalArgumentException("invalid format: '" + str + "' (expected: json or text)");
	}

	/**
	 * payload를 갱신기에 넘길 value-only JSON으로 바꾼다.
	 *
	 * @param payload	UTF-8로 읽은 payload.
	 * @return	{@link #JSON}이면 그대로, {@link #TEXT}이면 JSON 문자열로 감싼 값.
	 */
	public String toValueJson(String payload) {
		return switch ( this ) {
			case JSON -> payload;
			case TEXT -> '"' + new String(JsonStringEncoder.getInstance().quoteAsString(payload)) + '"';
		};
	}
}
