package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.json.JacksonUtils;
import utils.stream.FStream;

import mdt.persistence.asset.AssetVariableException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiColumnCollectionListAssetVariableConfig extends AbstractJdbcAssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:jdbc:multi-column-list";
	private static final String FIELD_TABLE = "table";
	private static final String FIELD_WHERE_CLAUSE = "whereClause";
	private static final String FIELD_COLUMNS = "memberColumns";
	private static final String FIELD_COLUMN = "column";
	private static final String FIELD_SUBPATH = "subPath";
	
	private static final String READ_QUERY_FORMAT = "select %s from %s %s";
	
	private String m_table;
	private String m_whereClause;
	private List<ColumnToSubPath> m_memberColumnToSubPathMappings;
	private String m_readQuery;
	
	/** 역직렬화 전용 기본 생성자. */
	private MultiColumnCollectionListAssetVariableConfig()  {}

	/**
	 * 이 변수가 읽기 가능한지 여부를 반환한다.
	 *
	 * @return {@code readable}이 명시되었으면 그 값, 명시되지 않았으면 {@code true}.
	 */
	public boolean isReadable() {
		return true;
	}

	public boolean isUpdatable() {
		return false;
	}

	/**
	 * 컬럼과 하위 경로 사이의 매핑 목록을 반환한다.
	 *
	 * @return {@link ColumnToSubPath} 매핑 목록.
	 */
	public List<ColumnToSubPath> getMemberColumnToSubPathMappings() {
        return m_memberColumnToSubPathMappings;
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
			var colCsv = FStream.from(m_memberColumnToSubPathMappings)
								.map(ColumnToSubPath::getColumnExpr)
								.join(", ");
			m_readQuery = String.format(READ_QUERY_FORMAT, colCsv, m_table, m_whereClause);
		}
		return m_readQuery;
	}
	
	public String getUpdateQuery() {
		throw new AssetVariableException("update is disabled");
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
		gen.writeArrayFieldStart(FIELD_COLUMNS);
		for ( ColumnToSubPath mapping : m_memberColumnToSubPathMappings ) {
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
	public static MultiColumnCollectionListAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		MultiColumnCollectionListAssetVariableConfig config = new MultiColumnCollectionListAssetVariableConfig();
		config.loadFields(jnode);

		config.m_table = JacksonUtils.getStringField(jnode, FIELD_TABLE);
		config.m_whereClause = JacksonUtils.getStringField(jnode, FIELD_WHERE_CLAUSE);
		
		JsonNode colNode = JacksonUtils.getFieldOrNull(jnode, FIELD_COLUMNS);
		if ( colNode == null ) {
			String msg = String.format("Cannot find '%s' field in multi-column AssetVariable config, %s",
										FIELD_COLUMNS, jnode);
			throw new AssetVariableException(msg);
		}
		config.m_memberColumnToSubPathMappings
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
