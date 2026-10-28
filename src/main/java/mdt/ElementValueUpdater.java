package mdt;


/**
 * AAS 요소 하나의 값을 갱신하는 객체.
 * <p>
 * {@link FaaastRuntime#newValueUpdater(ElementLocation)}로 만든다. 만들 때 요소의 타입 정보를 조회해 두므로
 * 갱신할 때마다 다시 조회하지 않으며, 요소가 없으면 만드는 단계에서 실패한다.
 * 메시지를 받아 요소 값을 반영하는 엔드포인트(MQTT 등)가 사용한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface ElementValueUpdater {
	/**
	 * 갱신 대상 요소의 위치를 반환한다.
	 *
	 * @return	요소 위치.
	 */
	public ElementLocation getLocation();

	/**
	 * AAS value-only JSON을 요소에 반영한다.
	 *
	 * @param valueJson	요소 타입에 맞는 value-only JSON (예: 숫자 Property는 {@code 23.5},
	 * 					문자열 Property는 {@code "idle"}).
	 * @throws ElementUpdateException	값 파싱, 요소 조회, 저장 중 하나라도 실패한 경우.
	 */
	public void update(String valueJson) throws ElementUpdateException;
}
