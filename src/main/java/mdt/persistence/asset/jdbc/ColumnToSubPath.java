package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.Split;
import utils.json.JacksonDeserializationException;


/**
 * DB 컬럼과 SubmodelElementCollection 내 하위 경로(subPath) 사이의 매핑.
 * <p>
 * 컬럼 표현식은 조회 질의의 {@code SELECT} 목록에 그대로 들어가므로 {@code "alias.column"}, {@code "max(x)"},
 * {@code "x AS y"} 같은 표현식도 쓸 수 있다. 조회 결과는 이름이 아니라 {@code SELECT} 목록의 위치로 읽는다.
 * 쓰기 질의({@code INSERT}/{@code UPDATE})에는 표현식의 마지막 마침표 뒤 부분인 컬럼 이름
 * ({@link #getColumnName()})을 쓰므로, 쓰기를 사용하는 매핑의 표현식은 (한정자가 붙을 수 있는) 컬럼 이름이어야 한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
class ColumnToSubPath {
	private static final String FIELD_COLUMN = "column";
	private static final String FIELD_SUBPATH = "subPath";

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
	 * 쓰기 질의에 사용한다.
	 *
	 * @return 컬럼 이름.
	 */
	public String getColumnName() {
		return m_colName;
	}

	/**
	 * {@code [{"column": ..., "subPath": ...}, ...]} 형식의 매핑 배열을 읽는다.
	 *
	 * @param jnode	설정 JSON 노드.
	 * @param field	매핑 배열 필드 이름.
	 * @return	매핑 목록 (1개 이상).
	 * @throws JacksonDeserializationException	필드가 없거나 배열이 아니거나 비어 있는 경우, 항목의
	 * 			{@code column}·{@code subPath}가 비어 있지 않은 문자열이 아니거나 {@code subPath}가 중복된 경우.
	 */
	static List<ColumnToSubPath> parseList(JsonNode jnode, String field) {
		JsonNode arrayNode = jnode.get(field);
		if ( arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty() ) {
			throw new JacksonDeserializationException(String.format("'%s' must be a non-empty array: %s", field, jnode));
		}

		List<ColumnToSubPath> mappings = new ArrayList<>();
		Set<String> subPaths = new HashSet<>();
		for ( JsonNode itemNode: arrayNode ) {
			String where = "'" + field + "' item " + itemNode;
			String column = AbstractJdbcAssetVariableConfig.requireText(itemNode, FIELD_COLUMN, where);
			String subPath = AbstractJdbcAssetVariableConfig.requireText(itemNode, FIELD_SUBPATH, where);
			if ( !subPaths.add(subPath) ) {
				throw new JacksonDeserializationException(String.format("duplicate subPath in '%s': %s", field, subPath));
			}
			mappings.add(new ColumnToSubPath(column, subPath));
		}
		return mappings;
	}

	/**
	 * 매핑 목록을 {@link #parseList(JsonNode, String)}가 읽는 형식으로 기록한다.
	 *
	 * @param gen		JsonGenerator.
	 * @param field		매핑 배열 필드 이름.
	 * @param mappings	매핑 목록.
	 * @throws IOException	기록이 실패한 경우.
	 */
	static void writeList(JsonGenerator gen, String field, List<ColumnToSubPath> mappings) throws IOException {
		gen.writeArrayFieldStart(field);
		for ( ColumnToSubPath mapping : mappings ) {
			gen.writeStartObject();
			gen.writeStringField(FIELD_COLUMN, mapping.getColumnExpr());
			gen.writeStringField(FIELD_SUBPATH, mapping.getSubPath());
			gen.writeEndObject();
		}
		gen.writeEndArray();
	}

	@Override
	public String toString() {
		return String.format("%s -> %s", m_colExpr, m_subPath);
	}
}
