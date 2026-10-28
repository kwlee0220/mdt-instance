package mdt.endpoint.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;


/**
 * {@link DeliveryMode#ALL} 구독에서 처리 중·대기 중인 메시지가 상한({@code maxInFlightMessages})에 이르렀을 때의 동작.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public enum OverflowPolicy {
	/**
	 * 자리가 날 때까지 MQTT 콜백 쓰레드가 기다린다(기본값). 메시지를 버리지 않지만, 기다리는 동안 같은 broker 연결을
	 * 쓰는 다른 구독과 MQTT 자산 변수의 수신도 함께 늦어진다.
	 */
	WAIT("wait"),
	/**
	 * 새 메시지를 버린다. 다른 구독의 수신은 늦어지지 않지만 메시지가 유실된다. 버린 수는 로그로 알린다.
	 */
	DROP("drop");

	private final String m_jsonValue;

	private OverflowPolicy(String jsonValue) {
		m_jsonValue = jsonValue;
	}

	@JsonValue
	public String toJsonValue() {
		return m_jsonValue;
	}

	/**
	 * 설정 문자열을 해석한다. 대소문자를 구분하지 않는다.
	 *
	 * @param str	{@code wait} 또는 {@code drop}.
	 * @return	포화 정책.
	 * @throws IllegalArgumentException	알 수 없는 값인 경우.
	 */
	@JsonCreator
	public static OverflowPolicy fromString(String str) {
		for ( OverflowPolicy policy: values() ) {
			if ( policy.m_jsonValue.equalsIgnoreCase(str) ) {
				return policy;
			}
		}
		throw new IllegalArgumentException("invalid overflowPolicy: '" + str + "' (expected: wait or drop)");
	}
}
