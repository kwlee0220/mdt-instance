package mdt.persistence.asset.jdbc;

import mdt.persistence.asset.AssetVariableException;


/**
 * JDBC AssetVariable의 조회 결과에 행이 없을 때의 동작.
 * <p>
 * 설정의 {@code onNoRecord} 필드로 지정하며, 값은 대소문자를 구분하지 않는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public enum NoRecordPolicy {
	/**
	 * 이전 값(처음이면 모델의 초기값)을 그대로 돌려준다(기본값).
	 * 경고 로그는 행이 없어진 첫 조회에서 한 번만 남긴다.
	 */
	KEEP,
	/** 매핑된 요소의 값을 비운다. Property는 {@code null}이 되고, JSON으로 주고받는 요소는 바뀌지 않는다. */
	CLEAR,
	/** 읽기를 실패시킨다({@link AssetVariableException}). */
	FAIL
}
