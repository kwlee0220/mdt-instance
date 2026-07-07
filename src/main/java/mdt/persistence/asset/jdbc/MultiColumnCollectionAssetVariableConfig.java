package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.json.JacksonUtils;
import utils.stream.FStream;

import mdt.persistence.asset.AssetVariableException;


/**
 * 한 테이블 행의 여러 컬럼을 SubmodelElementCollection의 여러 하위 element에 매핑하는
 * {@link AbstractJdbcAssetVariableConfig} 설정.
 * <p>
 * 각 컬럼은 collection 내부의 하위 경로(subPath)와 짝지어지며({@link ColumnToSubPath}),
 * 조회 시 {@code table}과 {@code whereClause}로 한 행을 읽어 각 컬럼 값을 대응 하위 element에 채운다.
 * {@code whereClause}는 SQL에 그대로 덧붙여지므로 {@code WHERE} 키워드를 포함해야 한다.
 * <p>
 * 읽기 가능 여부는 {@code readable}로, 쓰기 동작은 {@code updateMode}로 결정된다.
 * <ul>
 *   <li>{@link UpdateMode#APPEND} — 매 갱신마다 새 행을 {@code INSERT}한다.</li>
 *   <li>{@link UpdateMode#UPDATE} — {@code whereClause}에 해당하는 행을 {@code UPDATE}한다.</li>
 *   <li>{@link UpdateMode#DISABLED} — 쓰기 비활성화(기본값).</li>
 * </ul>
 * 조회/갱신 SQL은 설정 항목으로부터 최초 호출 시 생성되어 캐시된다
 * ({@link #getReadQuery()}/{@link #getUpdateQuery()}). 직렬화 식별자는 {@value #SERIALIZATION_TYPE}이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiColumnCollectionAssetVariableConfig extends AbstractJdbcAssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:jdbc:multi-column";
	private static final String FIELD_TABLE = "table";
	private static final String FIELD_WHERE_CLAUSE = "whereClause";
	private static final String FIELD_READABLE= "readable";
	private static final String FIELD_UPDATE_MODE= "updateMode";
	private static final String FIELD_COLUMNS = "columns";
	private static final String FIELD_COLUMN = "column";
	private static final String FIELD_SUBPATH = "subPath";
	
	private static final String READ_QUERY_FORMAT = "select %s from %s %s";
	private static final String UPDATE_QUERY_FORMAT = "update %s set %s %s";
	private static final String INSERT_QUERY_FORMAT = "insert into %s (%s) values (%s)";
	
	/**
	 * 갱신 시 datasource에 적용할 동작 방식.
	 */
	public static enum UpdateMode {
		/** 매 갱신마다 새 행을 추가({@code INSERT})한다. */
		APPEND,
		/** {@code whereClause}에 해당하는 행을 갱신({@code UPDATE})한다. */
		UPDATE,
		/** 쓰기를 비활성화한다. */
		DISABLED
	}
	
	private String m_table;
	private String m_whereClause;
	private Boolean m_readable = null;
	private UpdateMode m_updateMode = UpdateMode.DISABLED;
	private List<ColumnToSubPath> m_columnToSubPathMappings;
	private String m_readQuery;
	private String m_updateQuery;
	
	/** 역직렬화 전용 기본 생성자. */
	private MultiColumnCollectionAssetVariableConfig()  {}

	/**
	 * 이 변수가 읽기 가능한지 여부를 반환한다.
	 *
	 * @return {@code readable}이 명시되었으면 그 값, 명시되지 않았으면 {@code true}.
	 */
	public boolean isReadable() {
		return m_readable != null ? m_readable : true;
	}

	/**
	 * 이 변수가 갱신 가능한지 여부를 반환한다.
	 *
	 * @return {@code updateMode}가 {@link UpdateMode#DISABLED}가 아니면 {@code true}.
	 */
	public boolean isUpdatable() {
		return m_updateMode != UpdateMode.DISABLED;
	}

	/**
	 * 컬럼과 하위 경로 사이의 매핑 목록을 반환한다.
	 *
	 * @return {@link ColumnToSubPath} 매핑 목록.
	 */
	public List<ColumnToSubPath> getColumnToSubPathMappings() {
        return m_columnToSubPathMappings;
    }

	/**
	 * 조회 질의를 반환한다.
	 * <p>
	 * 매핑된 컬럼들과 {@code table}, {@code whereClause}로부터 {@code SELECT} 질의를 구성하며,
	 * 최초 호출 시 생성하여 캐시한다.
	 *
	 * @return 조회 질의.
	 */
	public String getReadQuery() {
		if ( m_readQuery == null ) {
			var colCsv = FStream.from(m_columnToSubPathMappings)
								.map(ColumnToSubPath::getColumnExpr)
								.join(", ");
			m_readQuery = String.format(READ_QUERY_FORMAT, colCsv, m_table, m_whereClause);
		}
		return m_readQuery;
	}
	
	/**
	 * 갱신 질의를 반환한다.
	 * <p>
	 * {@code updateMode}에 따라 {@link UpdateMode#APPEND}이면 {@code INSERT},
	 * {@link UpdateMode#UPDATE}이면 {@code UPDATE} 질의를 구성하며, 최초 호출 시 생성하여 캐시한다.
	 *
	 * @return 갱신 질의.
	 * @throws AssetVariableException	쓰기가 비활성화({@link UpdateMode#DISABLED})된 경우.
	 */
	public String getUpdateQuery() {
		if ( m_updateMode == UpdateMode.DISABLED ) {
			throw new AssetVariableException("update is disabled");
		}
		
		if ( m_updateQuery == null ) {
			if ( m_updateMode == UpdateMode.APPEND ) {
				var colCsv = FStream.from(m_columnToSubPathMappings)
									.map(ColumnToSubPath::getColumnExpr)
									.join(", ");
				var valueHolderCsv = FStream.range(0, m_columnToSubPathMappings.size())
											.map(i -> "?")
											.join(", ");
				m_updateQuery = String.format(INSERT_QUERY_FORMAT, m_table, colCsv, valueHolderCsv);
			}
			else {
				String setClause = FStream.from(m_columnToSubPathMappings)
											.map(mapping -> String.format("%s = ?", mapping.getColumnExpr()))
											.join(", ");
				m_updateQuery = String.format(UPDATE_QUERY_FORMAT, m_table, setClause, m_whereClause);
			}
		}
		return m_updateQuery;
	}

	/**
	 * 이 설정의 직렬화 식별자({@value #SERIALIZATION_TYPE})를 반환한다.
	 *
	 * @return 직렬화 식별자.
	 */
	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}

	/**
	 * 이 설정의 필드들을 주어진 {@link JsonGenerator}로 직렬화한다.
	 * <p>
	 * 공통 필드는 상위 클래스가 직렬화하고, 여기서는 table·whereClause·readable(설정된 경우)·
	 * updateMode·컬럼 매핑 배열을 추가로 기록한다.
	 *
	 * @param gen	직렬화에 사용할 JsonGenerator.
	 * @throws IOException	직렬화가 실패한 경우.
	 */
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);

		gen.writeStringField(FIELD_TABLE, m_table);
		gen.writeStringField(FIELD_WHERE_CLAUSE, m_whereClause);
		if ( m_readable != null ) {
			gen.writeBooleanField(FIELD_READABLE, m_readable);
		}
		gen.writeStringField(FIELD_UPDATE_MODE, m_updateMode.name());
		gen.writeArrayFieldStart(FIELD_COLUMNS);
		for ( ColumnToSubPath mapping : m_columnToSubPathMappings ) {
			gen.writeStartObject();
			gen.writeStringField(FIELD_COLUMN, mapping.getColumnExpr());
			gen.writeStringField(FIELD_SUBPATH, mapping.getSubPath());
			gen.writeEndObject();
		}
		gen.writeEndArray();
	}

	/**
	 * JSON 노드로부터 {@code MultiColumnCollectionAssetVariableConfig} 객체를 생성한다.
	 *
	 * @param jnode	JSON 노드.
	 * @return	생성된 설정 객체.
	 * @throws IOException	역직렬화가 실패하거나 {@code columns} 필드가 없는 경우.
	 */
	public static MultiColumnCollectionAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		MultiColumnCollectionAssetVariableConfig config = new MultiColumnCollectionAssetVariableConfig();
		config.loadFields(jnode);

		config.m_table = JacksonUtils.getStringField(jnode, FIELD_TABLE);
		config.m_whereClause = JacksonUtils.getStringField(jnode, FIELD_WHERE_CLAUSE);
		
		config.m_readable = JacksonUtils.getBooleanField(jnode, FIELD_READABLE, null);
		
		String modeStr = JacksonUtils.getStringFieldOrDefault(jnode, FIELD_UPDATE_MODE, "DISABLED");
		config.m_updateMode = UpdateMode.valueOf(modeStr);
		
		JsonNode colNode = JacksonUtils.getFieldOrNull(jnode, FIELD_COLUMNS);
		if ( colNode == null ) {
			String msg = String.format("Cannot find '%s' field in multi-column AssetVariable config, %s",
										FIELD_COLUMNS, jnode);
			throw new AssetVariableException(msg);
		}
		config.m_columnToSubPathMappings
							= FStream.from(colNode.elements())
									.mapOrThrow(fieldJnode -> {
										String column = JacksonUtils.getStringField(fieldJnode, FIELD_COLUMN);
										String subPath = JacksonUtils.getStringField(fieldJnode, FIELD_SUBPATH);
										return new ColumnToSubPath(column, subPath);
									})
									.toList();
		
		return config;
	}
}
