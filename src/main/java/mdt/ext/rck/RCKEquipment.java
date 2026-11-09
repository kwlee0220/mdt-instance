package mdt.ext.rck;

/**
 * 시뮬레이터의 설비 하나.
 *
 * @param group	설비 그룹. {@code facility} 메시지의 필드 이름(예: {@code Press})으로, 여러 설비가 공유할 수 있다.
 * @param name	설비 이름(예: {@code TP-75}).
 * @param index	설비 번호.
 * @author Kang-Woo Lee (ETRI)
 */
public record RCKEquipment(String group, String name, int index) {
	/**
	 * 설비를 {@code group[index](name)} 형식의 문자열(예: {@code Press[1](TP-75)})로 반환한다.
	 *
	 * @return	설비 문자열.
	 */
	@Override
	public String toString() {
		return String.format("%s[%d](%s)", group, index, name);
	}
}
