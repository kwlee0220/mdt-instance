package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.Preconditions;
import utils.json.JacksonDeserializationException;
import utils.json.JacksonUtils;
import utils.stream.FStream;

import mdt.persistence.asset.AssetVariableException;


/**
 * 한 테이블 행의 여러 컬럼을 SubmodelElementCollection의 여러 하위 element에 매핑하는
 * {@link AbstractJdbcAssetVariableConfig} 설정.
 * <p>
 * 각 컬럼은 collection 내부의 하위 경로(subPath)와 짝지어지며({@link ColumnToSubPath}),
 * 조회 시 {@code SELECT <컬럼들> FROM <table> ...}로 한 행을 읽어 각 컬럼 값을 대응 하위 element에 채운다.
 * {@code table}에는 조인 식도 쓸 수 있다. 읽을 행은 {@code where}·{@code orderBy}·{@code limit}(모두 선택) 또는
 * 이전 방식의 {@code whereClause}로 고른다({@link RowSelection}). 예를 들어 가장 최근 행은
 * {@code "orderBy": "id desc", "limit": 1}로 읽는다.
 * <p>
 * 읽기 가능 여부는 {@code readable}로, 쓰기 동작은 {@code updateMode}(대소문자 무관)로 결정된다.
 * <ul>
 *   <li>{@link UpdateMode#APPEND} — 매 갱신마다 새 행을 {@code INSERT}한다.</li>
 *   <li>{@link UpdateMode#UPDATE} — {@code where}(또는 {@code whereClause})에 해당하는 행을 {@code UPDATE}한다.
 *       조건이 없으면 모든 행이 갱신되므로 필수다. {@code orderBy}와 {@code limit}은 쓰이지 않는다.</li>
 *   <li>{@link UpdateMode#DISABLED} — 쓰기 비활성화(기본값).</li>
 * </ul>
 * 쓰기 질의에는 컬럼 표현식에서 한정자를 뗀 컬럼 이름({@link ColumnToSubPath#getColumnName()})을 쓴다.
 * <p>
 * 조회 결과에 행이 없을 때의 동작은 {@code onNoRecord}(대소문자 무관)로 정한다({@link NoRecordPolicy}).
 * <p>
 * 조회/갱신 SQL은 설정 항목으로부터 최초 호출 시 생성되어 캐시된다
 * ({@link #getReadQuery()}/{@link #getUpdateQuery()}). 직렬화 식별자는 {@value #SERIALIZATION_TYPE}이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiColumnCollectionAssetVariableConfig extends AbstractJdbcAssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:jdbc:multi-column";
	private static final String FIELD_TABLE = "table";
	private static final String FIELD_READABLE= "readable";
	private static final String FIELD_UPDATE_MODE= "updateMode";
	private static final String FIELD_ON_NO_RECORD = "onNoRecord";
	private static final String FIELD_COLUMNS = "columns";
	private static final Set<String> FIELDS;
	static {
		Set<String> fields = new HashSet<>(RowSelection.FIELDS);
		fields.addAll(List.of(FIELD_TABLE, FIELD_READABLE, FIELD_UPDATE_MODE, FIELD_ON_NO_RECORD, FIELD_COLUMNS));
		FIELDS = Set.copyOf(fields);
	}

	private static final String READ_QUERY_FORMAT = "select %s from %s %s";
	private static final String UPDATE_QUERY_FORMAT = "update %s set %s %s";
	private static final String INSERT_QUERY_FORMAT = "insert into %s (%s) values (%s)";

	/**
	 * 갱신 시 datasource에 적용할 동작 방식.
	 */
	public static enum UpdateMode {
		/** 매 갱신마다 새 행을 추가({@code INSERT})한다. */
		APPEND,
		/** {@code where}(또는 {@code whereClause})에 해당하는 행을 갱신({@code UPDATE})한다. */
		UPDATE,
		/** 쓰기를 비활성화한다. */
		DISABLED
	}

	private String m_table;
	private RowSelection m_rowSelection;
	private Boolean m_readable = null;
	private UpdateMode m_updateMode = UpdateMode.DISABLED;
	private NoRecordPolicy m_noRecordPolicy = NoRecordPolicy.KEEP;
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
	 * 조회 결과에 행이 없을 때의 동작을 반환한다.
	 *
	 * @return	동작. 설정되지 않았으면 {@link NoRecordPolicy#KEEP}.
	 */
	public NoRecordPolicy getNoRecordPolicy() {
		return m_noRecordPolicy;
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
	 * 매핑된 컬럼들과 {@code table}, 행 선택 조건으로부터 {@code SELECT} 질의를 구성하며,
	 * 최초 호출 시 생성하여 캐시한다. 결과 컬럼의 순서는 매핑 순서와 같다.
	 *
	 * @return 조회 질의.
	 */
	public String getReadQuery() {
		if ( m_readQuery == null ) {
			var colCsv = FStream.from(m_columnToSubPathMappings)
								.map(ColumnToSubPath::getColumnExpr)
								.join(", ");
			m_readQuery = String.format(READ_QUERY_FORMAT, colCsv, m_table, m_rowSelection.getReadTail()).trim();
		}
		return m_readQuery;
	}

	/**
	 * 갱신 질의를 반환한다.
	 * <p>
	 * {@code updateMode}에 따라 {@link UpdateMode#APPEND}이면 {@code INSERT},
	 * {@link UpdateMode#UPDATE}이면 {@code UPDATE} 질의를 구성하며, 최초 호출 시 생성하여 캐시한다.
	 * 파라미터({@code ?})의 순서는 매핑 순서와 같다.
	 *
	 * @return 갱신 질의.
	 * @throws AssetVariableException	쓰기가 비활성화({@link UpdateMode#DISABLED})된 경우.
	 */
	public String getUpdateQuery() {
		if ( m_updateMode == UpdateMode.DISABLED ) {
			throw new AssetVariableException("update is disabled");
		}

		if ( m_updateQuery == null ) {
			List<ColumnToSubPath> writables = FStream.from(getWritableMappingIndices())
													.map(m_columnToSubPathMappings::get)
													.toList();
			if ( m_updateMode == UpdateMode.APPEND ) {
				var colCsv = FStream.from(writables)
									.map(ColumnToSubPath::getColumnName)
									.join(", ");
				var valueHolderCsv = FStream.range(0, writables.size())
											.map(i -> "?")
											.join(", ");
				m_updateQuery = String.format(INSERT_QUERY_FORMAT, m_table, colCsv, valueHolderCsv);
			}
			else {
				m_updateQuery = getUpdateQuery(getWritableMappingIndices());
			}
		}
		return m_updateQuery;
	}
	
	/**
	 * 쓰기 질의에 포함되는 매핑({@code readOnly}가 아닌 매핑)의 위치 목록을 반환한다.
	 * <p>
	 * {@link #getUpdateQuery()}의 파라미터({@code ?}) 순서는 이 목록의 순서와 같다.
	 *
	 * @return	매핑 위치 목록.
	 */
	public List<Integer> getWritableMappingIndices() {
		return FStream.range(0, m_columnToSubPathMappings.size())
						.filter(idx -> m_columnToSubPathMappings.get(idx).isWritable())
						.toList();
	}
	
	/**
	 * 쓰기 동작 방식을 반환한다.
	 *
	 * @return	쓰기 동작 방식.
	 */
	public UpdateMode getUpdateMode() {
		return m_updateMode;
	}
	
	/**
	 * {@link UpdateMode#UPDATE} 모드에서 주어진 매핑의 컬럼만 갱신하는 질의를 반환한다.
	 * <p>
	 * 부분 갱신에서 바뀐 하위 요소에 해당하는 컬럼만 저장하여, 다른 컬럼의 값을 덮어쓰지 않기 위해 쓴다.
	 * 파라미터({@code ?})의 순서는 {@code mappingIndices}의 순서와 같다.
	 *
	 * @param mappingIndices	갱신할 매핑의 위치 목록 (1개 이상).
	 * @return	갱신 질의.
	 * @throws IllegalStateException	{@link UpdateMode#UPDATE} 모드가 아닌 경우.
	 */
	public String getUpdateQuery(List<Integer> mappingIndices) {
		Preconditions.checkState(m_updateMode == UpdateMode.UPDATE, "not UPDATE mode: %s", m_updateMode);
		Preconditions.checkArgument(!mappingIndices.isEmpty(), "no column to update");
		
		String setClause = FStream.from(mappingIndices)
									.map(idx -> String.format("%s = ?",
																m_columnToSubPathMappings.get(idx).getColumnName()))
									.join(", ");
		return String.format(UPDATE_QUERY_FORMAT, m_table, setClause, m_rowSelection.getUpdateTail()).trim();
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
	 * 공통 필드는 상위 클래스가 직렬화하고, 여기서는 table·행 선택 조건(설정된 것만)·readable(설정된 경우)·
	 * updateMode·onNoRecord(기본값이 아닌 경우)·컬럼 매핑 배열을 추가로 기록한다.
	 *
	 * @param gen	직렬화에 사용할 JsonGenerator.
	 * @throws IOException	직렬화가 실패한 경우.
	 */
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);

		gen.writeStringField(FIELD_TABLE, m_table);
		m_rowSelection.serialize(gen);
		if ( m_readable != null ) {
			gen.writeBooleanField(FIELD_READABLE, m_readable);
		}
		gen.writeStringField(FIELD_UPDATE_MODE, m_updateMode.name());
		if ( m_noRecordPolicy != NoRecordPolicy.KEEP ) {
			gen.writeStringField(FIELD_ON_NO_RECORD, m_noRecordPolicy.name());
		}
		ColumnToSubPath.writeList(gen, FIELD_COLUMNS, m_columnToSubPathMappings);
	}

	/**
	 * JSON 노드로부터 {@code MultiColumnCollectionAssetVariableConfig} 객체를 생성한다.
	 *
	 * @param jnode	JSON 노드.
	 * @return	생성된 설정 객체.
	 * @throws IOException	역직렬화가 실패한 경우.
	 * @throws JacksonDeserializationException	{@code table}이나 {@code columns}가 없거나 잘못된 경우,
	 * 			{@code updateMode}·{@code onNoRecord} 값이 잘못된 경우, 행 선택 조건이 잘못된 경우,
	 * 			{@code UPDATE} 모드인데 {@code where}(또는 {@code whereClause})가 없는 경우.
	 */
	public static MultiColumnCollectionAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		MultiColumnCollectionAssetVariableConfig config = new MultiColumnCollectionAssetVariableConfig();
		config.loadFields(jnode);
		warnUnknownFields(jnode, FIELDS);

		String where = "multi-column AssetVariable config";
		config.m_table = requireText(jnode, FIELD_TABLE, where);
		config.m_rowSelection = RowSelection.parse(jnode);
		config.m_readable = JacksonUtils.getBooleanField(jnode, FIELD_READABLE, null);
		config.m_updateMode = parseEnum(UpdateMode.class, jnode, FIELD_UPDATE_MODE, UpdateMode.DISABLED);
		config.m_noRecordPolicy = parseEnum(NoRecordPolicy.class, jnode, FIELD_ON_NO_RECORD, NoRecordPolicy.KEEP);
		config.m_columnToSubPathMappings = ColumnToSubPath.parseList(jnode, FIELD_COLUMNS);

		if ( config.m_updateMode != UpdateMode.DISABLED ) {
			checkWritableMappings(config.m_columnToSubPathMappings, jnode);
		}
		if ( config.m_updateMode == UpdateMode.UPDATE && !config.m_rowSelection.hasRowFilter() ) {
			throw new JacksonDeserializationException(String.format(
					"'%s' (or '%s') is required in UPDATE mode (otherwise every row is updated): %s",
					RowSelection.FIELD_WHERE, RowSelection.FIELD_WHERE_CLAUSE, jnode));
		}

		return config;
	}
	
	/**
	 * 쓰기를 사용하는 설정에서 각 매핑의 쓰기 컬럼을 확인한다.
	 * <p>
	 * 함수식이나 별칭이 있는 표현식에서는 쓰기 컬럼 이름을 얻을 수 없으므로 {@code writeColumn}이나
	 * {@code readOnly}를 지정해야 한다. 쓰기에 포함되는 매핑이 하나도 없어도 설정 오류다.
	 */
	private static void checkWritableMappings(List<ColumnToSubPath> mappings, JsonNode jnode) {
		for ( ColumnToSubPath mapping: mappings ) {
			if ( mapping.isWritable() && !mapping.hasWriteColumn() ) {
				throw new JacksonDeserializationException(String.format(
						"cannot derive the column to write from '%s'; specify 'writeColumn' or set 'readOnly': %s",
						mapping.getColumnExpr(), jnode));
			}
		}
		if ( FStream.from(mappings).noneMatch(ColumnToSubPath::isWritable) ) {
			throw new JacksonDeserializationException(String.format(
					"every column is readOnly, but '%s' is not DISABLED: %s", FIELD_UPDATE_MODE, jnode));
		}
	}
}
