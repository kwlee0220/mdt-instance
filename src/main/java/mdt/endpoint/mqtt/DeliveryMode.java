package mdt.endpoint.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;


/**
 * MQTT 구독이 수신한 메시지를 AAS 요소에 반영하는 방식.
 * <p>
 * 두 방식 모두 반영 작업은 MQTT 콜백 쓰레드 밖의 작업 쓰레드에서 구독별로 순서대로 수행한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public enum DeliveryMode {
	/**
	 * 받은 모든 메시지를 받은 순서대로 반영한다(기본값). 반영이 밀리면 구독별 대기열에 쌓이며,
	 * 대기열이 가득 차면 새 메시지를 넣을 자리가 생길 때까지 MQTT 콜백 쓰레드가 기다린다.
	 */
	ALL("all"),
	/**
	 * 최신 값만 반영한다. 아직 반영하지 못한 이전 값은 새 메시지가 오면 버린다. 센서 값처럼 마지막 값만 의미 있는 경우에 쓴다.
	 */
	LATEST("latest");

	private final String m_jsonValue;

	private DeliveryMode(String jsonValue) {
		m_jsonValue = jsonValue;
	}

	@JsonValue
	public String toJsonValue() {
		return m_jsonValue;
	}

	/**
	 * 설정 문자열을 해석한다. 대소문자를 구분하지 않는다.
	 *
	 * @param str	{@code all} 또는 {@code latest}.
	 * @return	반영 방식.
	 * @throws IllegalArgumentException	알 수 없는 값인 경우.
	 */
	@JsonCreator
	public static DeliveryMode fromString(String str) {
		for ( DeliveryMode mode: values() ) {
			if ( mode.m_jsonValue.equalsIgnoreCase(str) ) {
				return mode;
			}
		}
		throw new IllegalArgumentException("invalid delivery: '" + str + "' (expected: all or latest)");
	}
}
