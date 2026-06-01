package mdt.persistence.asset.jdbc;

import utils.Split;


/**
 * DB 컬럼과 SubmodelElementCollection 내 하위 경로(subPath) 사이의 매핑.
 * <p>
 * 컬럼 표현식은 {@code "alias.column"}처럼 한정자를 포함할 수 있으며, 컬럼 이름은 표현식의
 * 마지막 마침표 뒤 부분에서 추출된다.
 * 
 * @author Kang-Woo Lee (ETRI)
 */
class ColumnToSubPath {
	private final String m_colExpr;		// 전체 컬럼 표현식 (예: "alias.column")
	private final String m_subPath;
	private final String m_colName;		// 컬럼 이름 (예: "column"), 컬럼 표현식에서 마지막 부분을 추출하여 저장

	/**
	 * 컬럼 표현식과 하위 경로로 매핑을 생성한다.
	 *
	 * @param colExpr	컬럼 표현식(예: {@code "alias.column"}).
	 * @param subPath	collection 내 하위 경로.
	 */
	public ColumnToSubPath(String colExpr, String subPath) {
		m_colExpr = colExpr;
		m_subPath = subPath;

		m_colName = Split.split(colExpr, ".").tail().orElse(m_colExpr);
	}

	public String getColumnExpr() {
		return m_colExpr;
	}

	public String getSubPath() {
		return m_subPath;
	}

	/**
	 * 컬럼 표현식에서 추출한 컬럼 이름을 반환한다(예: {@code "alias.column"} → {@code "column"}).
	 *
	 * @return 컬럼 이름.
	 */
	public String getColumnName() {
		return m_colName;
	}

	@Override
	public String toString() {
		return String.format("%s -> %s", m_colExpr, m_subPath);
	}
}