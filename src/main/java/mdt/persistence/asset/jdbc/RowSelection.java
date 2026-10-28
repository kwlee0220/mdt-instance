package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.json.JacksonDeserializationException;
import utils.json.JacksonUtils;


/**
 * multi-column 계열 설정에서 읽을 행을 고르는 조건.
 * <p>
 * 다음 두 방식 중 하나로 지정하며, 함께 쓸 수 없다.
 * <ul>
 *   <li>{@code where}, {@code orderBy}, {@code limit} — 각각 {@code WHERE}의 조건식, {@code ORDER BY}의 정렬식,
 *       {@code LIMIT}의 행 수이며 모두 선택 항목이다. 키워드({@code WHERE}, {@code ORDER BY})는 쓰지 않는다.
 *       {@code UPDATE} 질의에는 {@code where}만 쓰인다.</li>
 *   <li>{@code whereClause} — SQL 끝에 그대로 덧붙는 문자열(이전 방식). {@code WHERE}, {@code ORDER BY}, {@code LIMIT}
 *       등의 키워드를 포함해 쓰며, 조회와 {@code UPDATE} 질의 모두에 그대로 붙는다.</li>
 * </ul>
 * 직렬화할 때는 읽어 들인 방식을 유지한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
final class RowSelection {
	static final String FIELD_WHERE_CLAUSE = "whereClause";
	static final String FIELD_WHERE = "where";
	static final String FIELD_ORDER_BY = "orderBy";
	static final String FIELD_LIMIT = "limit";
	static final Set<String> FIELDS = Set.of(FIELD_WHERE_CLAUSE, FIELD_WHERE, FIELD_ORDER_BY, FIELD_LIMIT);

	private final String m_whereClause;		// 이전 방식. 쓰지 않으면 빈 문자열.
	private final @Nullable String m_where;
	private final @Nullable String m_orderBy;
	private final @Nullable Integer m_limit;

	private RowSelection(String whereClause, @Nullable String where, @Nullable String orderBy,
							@Nullable Integer limit) {
		m_whereClause = whereClause;
		m_where = where;
		m_orderBy = orderBy;
		m_limit = limit;
	}

	/**
	 * 행을 거르는 조건이 있는지 반환한다.
	 * <p>
	 * {@code UPDATE} 모드에서 조건이 없으면 모든 행이 갱신되므로 이를 막는 데 쓴다.
	 *
	 * @return	{@code where} 또는 {@code whereClause}가 지정되었으면 {@code true}.
	 */
	boolean hasRowFilter() {
		return m_where != null || !m_whereClause.isEmpty();
	}

	/**
	 * 조회 질의 끝에 붙일 절을 반환한다.
	 *
	 * @return	절. 조건이 없으면 빈 문자열.
	 */
	String getReadTail() {
		if ( !m_whereClause.isEmpty() ) {
			return m_whereClause;
		}

		StringBuilder builder = new StringBuilder();
		if ( m_where != null ) {
			builder.append("where ").append(m_where);
		}
		if ( m_orderBy != null ) {
			builder.append(builder.isEmpty() ? "" : " ").append("order by ").append(m_orderBy);
		}
		if ( m_limit != null ) {
			builder.append(builder.isEmpty() ? "" : " ").append("limit ").append(m_limit);
		}
		return builder.toString();
	}

	/**
	 * {@code UPDATE} 질의 끝에 붙일 절을 반환한다.
	 * <p>
	 * {@code orderBy}와 {@code limit}은 쓰지 않는다. {@code whereClause}는 이전과 같이 그대로 붙인다.
	 *
	 * @return	절. 조건이 없으면 빈 문자열.
	 */
	String getUpdateTail() {
		if ( !m_whereClause.isEmpty() ) {
			return m_whereClause;
		}
		return (m_where != null) ? "where " + m_where : "";
	}

	/**
	 * 지정된 필드만 직렬화한다.
	 *
	 * @param gen	직렬화에 사용할 JsonGenerator.
	 * @throws IOException	직렬화가 실패한 경우.
	 */
	void serialize(JsonGenerator gen) throws IOException {
		if ( !m_whereClause.isEmpty() ) {
			gen.writeStringField(FIELD_WHERE_CLAUSE, m_whereClause);
		}
		if ( m_where != null ) {
			gen.writeStringField(FIELD_WHERE, m_where);
		}
		if ( m_orderBy != null ) {
			gen.writeStringField(FIELD_ORDER_BY, m_orderBy);
		}
		if ( m_limit != null ) {
			gen.writeNumberField(FIELD_LIMIT, m_limit);
		}
	}

	/**
	 * JSON 노드에서 행 선택 조건을 읽는다.
	 *
	 * @param jnode	설정 JSON 노드.
	 * @return	행 선택 조건.
	 * @throws JacksonDeserializationException	{@code whereClause}를 다른 필드와 함께 쓴 경우, {@code where}나
	 * 			{@code orderBy}가 비었거나 키워드로 시작하는 경우, {@code limit}이 양의 정수가 아닌 경우.
	 */
	static RowSelection parse(JsonNode jnode) {
		String whereClause = JacksonUtils.getStringFieldOrDefault(jnode, FIELD_WHERE_CLAUSE, "").trim();
		String where = parseExpr(jnode, FIELD_WHERE, "where");
		String orderBy = parseExpr(jnode, FIELD_ORDER_BY, "order by");
		Integer limit = parseLimit(jnode);

		if ( !whereClause.isEmpty() && (where != null || orderBy != null || limit != null) ) {
			throw new JacksonDeserializationException(String.format(
					"'%s' cannot be combined with '%s', '%s' or '%s': %s",
					FIELD_WHERE_CLAUSE, FIELD_WHERE, FIELD_ORDER_BY, FIELD_LIMIT, jnode));
		}
		return new RowSelection(whereClause, where, orderBy, limit);
	}

	private static @Nullable String parseExpr(JsonNode jnode, String field, String keyword) {
		JsonNode node = jnode.get(field);
		if ( node == null || node.isNull() ) {
			return null;
		}
		String expr = node.isTextual() ? node.asText().trim() : "";
		if ( expr.isEmpty() ) {
			throw new JacksonDeserializationException(String.format("'%s' must be a non-blank string: %s",
																	field, jnode));
		}
		// "where where ..."처럼 키워드가 두 번 들어가지 않도록, 키워드는 쓰지 않게 한다.
		String lower = expr.toLowerCase(Locale.ROOT);
		if ( lower.startsWith(keyword + " ") || lower.equals(keyword) ) {
			throw new JacksonDeserializationException(String.format(
					"'%s' must not start with the '%s' keyword: %s", field, keyword.toUpperCase(Locale.ROOT), expr));
		}
		return expr;
	}

	private static @Nullable Integer parseLimit(JsonNode jnode) {
		JsonNode node = jnode.get(FIELD_LIMIT);
		if ( node == null || node.isNull() ) {
			return null;
		}
		if ( !node.canConvertToInt() || !node.isIntegralNumber() || node.asInt() <= 0 ) {
			throw new JacksonDeserializationException(String.format("'%s' must be a positive integer: %s",
																	FIELD_LIMIT, node));
		}
		return node.asInt();
	}
}
