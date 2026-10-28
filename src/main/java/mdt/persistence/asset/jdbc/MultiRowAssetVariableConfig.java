package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.func.Optionals;
import utils.json.JacksonDeserializationException;
import utils.json.JacksonUtils;
import utils.stream.KeyValueFStream;

import mdt.ElementLocation;

/**
 * {@link MultiRowAssetVariable}의 설정.
 * <ul>
 *   <li>{@code readQuery} — 행마다 첫 컬럼에 key, 둘째 컬럼에 값을 돌려주는 {@code SELECT} (필수).</li>
 *   <li>{@code updateQuery} — 첫 파라미터에 값, 둘째 파라미터에 key를 받는 {@code UPDATE} (선택, 없으면 읽기 전용).</li>
 *   <li>{@code rows} — {@code [{"key": ..., "subPath": ...}, ...]} 형식의 key와 하위 경로 매핑 (필수, 1개 이상).
 *       key는 중복될 수 없으며, 적힌 순서가 유지된다.</li>
 * </ul>
 * 직렬화 식별자는 {@value #SERIALIZATION_TYPE}이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiRowAssetVariableConfig extends AbstractJdbcAssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:jdbc:multi-row";

	private static final String FIELD_READ_QUERY = "readQuery";
	private static final String FIELD_UPDATE_QUERY = "updateQuery";
	private static final String FIELD_ROWS = "rows";
	private static final String FIELD_KEY = "key";
	private static final String FIELD_SUBPATH = "subPath";
	private static final Set<String> FIELDS = Set.of(FIELD_READ_QUERY, FIELD_UPDATE_QUERY, FIELD_ROWS);
	
	private String m_readQuery;
	private @Nullable String m_updateQuery;
	private Map<String,String> m_mapping;	// row key -> subpath mapping
	
	private MultiRowAssetVariableConfig()  { }
	public MultiRowAssetVariableConfig(ElementLocation elementLoc,
										@Nullable String jdbcConfigKey, @Nullable Duration validPeriod,
										String readQuery, @Nullable String updateQuery,
										Map<String,String> keyToSubpathMappings) {
		super(elementLoc, jdbcConfigKey, validPeriod);
		
		m_readQuery = readQuery;
		m_updateQuery = updateQuery;
		m_mapping = new LinkedHashMap<>(keyToSubpathMappings);
	}
	
	public String getReadQuery() {
		return m_readQuery;
	}
	
	public String getUpdateQuery() {
		return m_updateQuery;
	}
	
	/**
	 * key와 하위 경로 사이의 매핑을 반환한다. 설정에 적힌 순서를 유지하며, 바꿀 수 없다.
	 *
	 * @return	key → 하위 경로 매핑.
	 */
	public Map<String,String> getKeyToSubpathMapping() {
		return Collections.unmodifiableMap(m_mapping);
	}

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}

	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);

		gen.writeStringField(FIELD_READ_QUERY, m_readQuery);
		Optionals.acceptThrow(m_updateQuery, q -> gen.writeStringField(FIELD_UPDATE_QUERY, q));
		
		gen.writeArrayFieldStart(FIELD_ROWS);
		KeyValueFStream.from(this.m_mapping).forEachOrThrow(kv -> {
			gen.writeStartObject();
			gen.writeStringField(FIELD_KEY, kv.key());
			gen.writeStringField(FIELD_SUBPATH, kv.value());
			gen.writeEndObject();
		});
		gen.writeEndArray();
	}

	/**
	 * JSON 노드로부터 {@link MultiRowAssetVariableConfig} 객체를 생성한다.
	 *
	 * @param jnode	JSON 노드.
	 * @return	생성된 설정 객체.
	 * @throws IOException	역직렬화가 실패한 경우.
	 * @throws JacksonDeserializationException	{@code readQuery}나 {@code rows}가 없거나 잘못된 경우,
	 * 			key가 중복된 경우.
	 */
	public static MultiRowAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		MultiRowAssetVariableConfig config = new MultiRowAssetVariableConfig();
		config.loadFields(jnode);
		warnUnknownFields(jnode, FIELDS);
		
		String where = "multi-row AssetVariable config";
		config.m_readQuery = requireText(jnode, FIELD_READ_QUERY, where);
		config.m_updateQuery = JacksonUtils.getStringFieldOrNull(jnode, FIELD_UPDATE_QUERY);

		JsonNode rowsNode = jnode.get(FIELD_ROWS);
		if ( rowsNode == null || !rowsNode.isArray() || rowsNode.isEmpty() ) {
			throw new JacksonDeserializationException(String.format("'%s' must be a non-empty array: %s",
																	FIELD_ROWS, jnode));
		}
		config.m_mapping = new LinkedHashMap<>();
		for ( JsonNode rowNode: rowsNode ) {
			String rowWhere = "'" + FIELD_ROWS + "' item " + rowNode;
			String key = requireText(rowNode, FIELD_KEY, rowWhere);
			String subPath = requireText(rowNode, FIELD_SUBPATH, rowWhere);
			if ( config.m_mapping.put(key, subPath) != null ) {
				throw new JacksonDeserializationException(String.format("duplicate key in '%s': %s", FIELD_ROWS, key));
			}
		}
		
		return config;
	}
}
