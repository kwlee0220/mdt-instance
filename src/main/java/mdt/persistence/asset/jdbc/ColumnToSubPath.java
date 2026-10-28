package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.Preconditions;
import utils.Split;
import utils.json.JacksonDeserializationException;


/**
 * DB 컬럼과 SubmodelElementCollection 내 하위 경로(subPath) 사이의 매핑.
 * <p>
 * 컬럼 표현식은 조회 질의의 {@code SELECT} 목록에 그대로 들어가므로 {@code "alias.column"}, {@code "max(x)"},
 * {@code "x AS y"} 같은 표현식도 쓸 수 있다. 조회 결과는 이름이 아니라 {@code SELECT} 목록의 위치로 읽는다.
 * 쓰기 질의({@code INSERT}/{@code UPDATE})에는 컬럼 이름({@link #getColumnName()})을 쓴다. 컬럼 이름은
 * {@code writeColumn}이 있으면 그 값이고, 없으면 표현식의 마지막 마침표 뒤 부분이다.
 * <ul>
 *   <li>{@code writeColumn} — 쓰기에 쓸 컬럼 이름. 함수식·별칭처럼 표현식에서 컬럼 이름을 얻을 수 없을 때 지정한다.</li>
 *   <li>{@code readOnly} — {@code true}이면 이 매핑은 읽기에만 쓰이고 쓰기 질의에서 빠진다(계산 컬럼 등).
 *       {@code writeColumn}과 함께 쓸 수 없다.</li>
 * </ul>
 *
 * @author Kang-Woo Lee (ETRI)
 */
class ColumnToSubPath {
	private static final String FIELD_COLUMN = "column";
	private static final String FIELD_SUBPATH = "subPath";
	private static final String FIELD_WRITE_COLUMN = "writeColumn";
	private static final String FIELD_READ_ONLY = "readOnly";

	private final String m_colExpr;		// 전체 컬럼 표현식 (예: "alias.column")
	private final String m_subPath;
	private final String m_colName;		// 쓰기용 컬럼 이름: writeColumn 또는 컬럼 표현식의 마지막 이름 조각
	private final boolean m_plainColumn;	// 컬럼 표현식이 (한정자가 붙을 수 있는) 단순 컬럼 이름인지 여부
	private final @Nullable String m_writeColumn;
	private final boolean m_readOnly;

	/**
	 * 컬럼 표현식과 하위 경로로 매핑을 생성한다.
	 *
	 * @param colExpr	컬럼 표현식(예: {@code "alias.column"}).
	 * @param subPath	collection 내 하위 경로.
	 */
	public ColumnToSubPath(String colExpr, String subPath) {
		this(colExpr, subPath, null, false);
	}

	/**
	 * 컬럼 표현식, 하위 경로, 쓰기 설정으로 매핑을 생성한다.
	 *
	 * @param colExpr		컬럼 표현식.
	 * @param subPath		collection 내 하위 경로.
	 * @param writeColumn	쓰기에 쓸 컬럼 이름. {@code null}이면 컬럼 표현식에서 얻는다.
	 * @param readOnly		읽기 전용 여부.
	 */
	public ColumnToSubPath(String colExpr, String subPath, @Nullable String writeColumn, boolean readOnly) {
		Preconditions.checkArgument(!(readOnly && writeColumn != null),
									"'%s' cannot be used with '%s': %s", FIELD_WRITE_COLUMN, FIELD_READ_ONLY, colExpr);
		m_colExpr = colExpr;
		m_subPath = subPath;
		m_writeColumn = writeColumn;
		m_readOnly = readOnly;

		List<String> names = splitQualifiedName(colExpr.trim());
		m_plainColumn = names != null;
		if ( writeColumn != null ) {
			m_colName = writeColumn;
		}
		else if ( names != null ) {
			m_colName = names.get(names.size() - 1);
		}
		else {
			// 함수식·별칭 등은 쓰기에 쓸 수 없지만(설정 검사에서 거부), 이전과 같이 마지막 점 뒤 부분을 둔다.
			m_colName = Split.split(colExpr, ".").tail().orElse(m_colExpr);
		}
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
	 * 쓰기 질의에 포함되는 매핑인지 반환한다.
	 *
	 * @return	{@code readOnly}가 아니면 {@code true}.
	 */
	public boolean isWritable() {
		return !m_readOnly;
	}
	
	/**
	 * 쓰기에 쓸 컬럼 이름을 확실히 알 수 있는지 반환한다.
	 * <p>
	 * {@code writeColumn}이 지정되었거나, 컬럼 표현식이 (한정자가 붙을 수 있는) 단순 컬럼 이름이면 {@code true}이다.
	 * 함수식이나 별칭이 있는 표현식은 {@code writeColumn} 없이 쓸 수 없다.
	 *
	 * @return	쓰기 컬럼 이름을 알 수 있으면 {@code true}.
	 */
	boolean hasWriteColumn() {
		return m_writeColumn != null || m_plainColumn;
	}
	
	/**
	 * 컬럼 표현식을 점({@code .})으로 구분된 이름 조각들로 나눈다.
	 * <p>
	 * 이름 조각은 일반 이름({@code [A-Za-z0-9_$]+}), 큰따옴표로 인용한 이름({@code "Sensor Value"}, 안의 {@code ""}는
	 * 따옴표 하나), 백틱으로 인용한 이름({@code `col`}), 대괄호로 인용한 이름({@code [col]})이다. 인용한 이름 안의
	 * 점과 공백은 이름의 일부다. 각 조각은 인용 부호를 포함한 원래 표기 그대로 반환한다.
	 *
	 * @param expr	컬럼 표현식.
	 * @return	이름 조각 목록. 표현식이 이름 조각들로만 이루어져 있지 않으면(함수식, 별칭, 공백 등) {@code null}.
	 */
	static @Nullable List<String> splitQualifiedName(String expr) {
		List<String> names = new ArrayList<>();
		int i = 0;
		int len = expr.length();
		while ( i < len ) {
			int start = i;
			char c = expr.charAt(i);
			if ( c == '"' || c == '`' || c == '[' ) {
				char close = (c == '[') ? ']' : c;
				i = i + 1;
				while ( true ) {
					if ( i >= len ) {
						return null;	// 인용이 닫히지 않음
					}
					if ( expr.charAt(i) == close ) {
						// 인용 부호를 두 번 쓰면 이름 안의 인용 부호 하나다(예: "a""b").
						if ( close != ']' && i + 1 < len && expr.charAt(i + 1) == close ) {
							i += 2;
							continue;
						}
						i = i + 1;
						break;
					}
					++i;
				}
				if ( i - start <= 2 ) {
					return null;	// 빈 인용 이름
				}
			}
			else {
				while ( i < len && isNameChar(expr.charAt(i)) ) {
					++i;
				}
				if ( i == start ) {
					return null;	// 이름이 아닌 문자 (괄호, 공백, 연산자 등)
				}
			}
			names.add(expr.substring(start, i));
			
			if ( i == len ) {
				break;
			}
			if ( expr.charAt(i) != '.' || i + 1 == len ) {
				return null;	// 이름 조각 뒤에는 점이 와야 하고, 점으로 끝날 수 없다.
			}
			++i;
		}
		return names.isEmpty() ? null : names;
	}
	
	private static boolean isNameChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$';
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
			String writeColumn = itemNode.has(FIELD_WRITE_COLUMN)
								? AbstractJdbcAssetVariableConfig.requireText(itemNode, FIELD_WRITE_COLUMN, where) : null;
			JsonNode readOnlyNode = itemNode.get(FIELD_READ_ONLY);
			if ( readOnlyNode != null && !readOnlyNode.isBoolean() ) {
				throw new JacksonDeserializationException(String.format("'%s' must be a boolean: %s",
																		FIELD_READ_ONLY, where));
			}
			boolean readOnly = readOnlyNode != null && readOnlyNode.booleanValue();
			if ( readOnly && writeColumn != null ) {
				throw new JacksonDeserializationException(String.format("'%s' cannot be used with '%s': %s",
																		FIELD_WRITE_COLUMN, FIELD_READ_ONLY, where));
			}
			mappings.add(new ColumnToSubPath(column, subPath, writeColumn, readOnly));
		}
		// 바인딩과 SQL은 매핑 순서에 의존하므로, 읽은 뒤에는 목록을 바꿀 수 없게 한다.
		return List.copyOf(mappings);
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
			if ( mapping.m_writeColumn != null ) {
				gen.writeStringField(FIELD_WRITE_COLUMN, mapping.m_writeColumn);
			}
			if ( mapping.m_readOnly ) {
				gen.writeBooleanField(FIELD_READ_ONLY, true);
			}
			gen.writeEndObject();
		}
		gen.writeEndArray();
	}

	@Override
	public String toString() {
		return String.format("%s -> %s", m_colExpr, m_subPath);
	}
}
