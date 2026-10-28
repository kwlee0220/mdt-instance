package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.json.JacksonDeserializationException;
import utils.stream.FStream;


/**
 * 조회 결과의 각 행을 SubmodelElementList의 원소(SubmodelElementCollection) 하나로 만드는
 * {@link MultiColumnCollectionListAssetVariable}의 설정.
 * <p>
 * {@code columns}의 각 항목은 행의 컬럼과 원소 내부의 하위 경로(subPath)를 짝짓는다({@link ColumnToSubPath}).
 * 이전 이름인 {@code memberColumns}도 받으며, 직렬화할 때는 읽어 들인 이름을 유지한다.
 * 조회 질의는 {@code SELECT <컬럼들> FROM <table> ...}로 구성되며, 읽을 행은 {@code where}·{@code orderBy}·
 * {@code limit}(모두 선택) 또는 이전 방식의 {@code whereClause}로 고른다({@link RowSelection}). 이 변수는 읽기 전용이다.
 * 직렬화 식별자는 {@value #SERIALIZATION_TYPE}이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiColumnCollectionListAssetVariableConfig extends AbstractJdbcAssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:jdbc:multi-column-list";
	private static final String FIELD_TABLE = "table";
	private static final String FIELD_COLUMNS = "columns";
	private static final String FIELD_MEMBER_COLUMNS = "memberColumns";	// 이전 이름
	private static final Set<String> FIELDS;
	static {
		Set<String> fields = new HashSet<>(RowSelection.FIELDS);
		fields.addAll(List.of(FIELD_TABLE, FIELD_COLUMNS, FIELD_MEMBER_COLUMNS));
		FIELDS = Set.copyOf(fields);
	}

	private static final String READ_QUERY_FORMAT = "select %s from %s %s";

	private String m_table;
	private RowSelection m_rowSelection;
	private List<ColumnToSubPath> m_memberColumnToSubPathMappings;
	private String m_columnsField = FIELD_COLUMNS;	// 직렬화할 때 쓸 매핑 필드 이름
	private String m_readQuery;

	/** 역직렬화 전용 기본 생성자. */
	private MultiColumnCollectionListAssetVariableConfig()  {}

	/**
	 * 이 변수가 읽기 가능한지 여부를 반환한다.
	 *
	 * @return 항상 {@code true}.
	 */
	public boolean isReadable() {
		return true;
	}

	/**
	 * 이 변수가 갱신 가능한지 여부를 반환한다.
	 *
	 * @return 항상 {@code false} (읽기 전용).
	 */
	public boolean isUpdatable() {
		return false;
	}

	/**
	 * 컬럼과 원소 내부 하위 경로 사이의 매핑 목록을 반환한다.
	 *
	 * @return {@link ColumnToSubPath} 매핑 목록.
	 */
	public List<ColumnToSubPath> getMemberColumnToSubPathMappings() {
        return m_memberColumnToSubPathMappings;
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
			var colCsv = FStream.from(m_memberColumnToSubPathMappings)
								.map(ColumnToSubPath::getColumnExpr)
								.join(", ");
			m_readQuery = String.format(READ_QUERY_FORMAT, colCsv, m_table, m_rowSelection.getReadTail()).trim();
		}
		return m_readQuery;
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
	 * 공통 필드는 상위 클래스가 직렬화하고, 여기서는 table·행 선택 조건(설정된 것만)·원소 컬럼 매핑 배열을
	 * 추가로 기록한다. 매핑 배열은 읽어 들일 때의 필드 이름({@code columns} 또는 {@code memberColumns})으로 기록한다.
	 *
	 * @param gen	직렬화에 사용할 JsonGenerator.
	 * @throws IOException	직렬화가 실패한 경우.
	 */
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);

		gen.writeStringField(FIELD_TABLE, m_table);
		m_rowSelection.serialize(gen);
		ColumnToSubPath.writeList(gen, m_columnsField, m_memberColumnToSubPathMappings);
	}

	/**
	 * JSON 노드로부터 {@code MultiColumnCollectionListAssetVariableConfig} 객체를 생성한다.
	 *
	 * @param jnode	JSON 노드.
	 * @return	생성된 설정 객체.
	 * @throws IOException	역직렬화가 실패한 경우.
	 * @throws JacksonDeserializationException	{@code table}이나 {@code columns}({@code memberColumns})가 없거나
	 * 			잘못된 경우, 두 이름을 함께 쓴 경우, 행 선택 조건이 잘못된 경우.
	 */
	public static MultiColumnCollectionListAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		MultiColumnCollectionListAssetVariableConfig config = new MultiColumnCollectionListAssetVariableConfig();
		config.loadFields(jnode);
		warnUnknownFields(jnode, FIELDS);

		config.m_table = requireText(jnode, FIELD_TABLE, "multi-column-list AssetVariable config");
		config.m_rowSelection = RowSelection.parse(jnode);
		if ( jnode.has(FIELD_COLUMNS) && jnode.has(FIELD_MEMBER_COLUMNS) ) {
			throw new JacksonDeserializationException(String.format("'%s' and '%s' cannot be used together: %s",
																	FIELD_COLUMNS, FIELD_MEMBER_COLUMNS, jnode));
		}
		config.m_columnsField = jnode.has(FIELD_MEMBER_COLUMNS) ? FIELD_MEMBER_COLUMNS : FIELD_COLUMNS;
		config.m_memberColumnToSubPathMappings = ColumnToSubPath.parseList(jnode, config.m_columnsField);

		return config;
	}
}
