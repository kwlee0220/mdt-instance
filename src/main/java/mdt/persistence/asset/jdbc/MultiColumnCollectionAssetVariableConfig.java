package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.json.JacksonUtils;
import utils.stream.FStream;

import mdt.ElementLocation;
import mdt.persistence.asset.AssetVariableConfig;
import mdt.persistence.asset.AssetVariableException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiColumnCollectionAssetVariableConfig extends AbstractJdbcAssetVariableConfig
														implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:jdbc:multi-column";
	private static final String FIELD_TABLE = "table";
	private static final String FIELD_WHERE_CLAUSE = "whereClause";
	private static final String FIELD_COLUMNS = "columns";
	private static final String FIELD_COLUMN = "column";
	private static final String FIELD_SUBPATH = "subPath";
	
	private static String READ_QUERY_FORMAT = "select %s from %s %s";
	private static String UPDATE_QUERY_FORMAT = "update %s set %s %s";
	private static String INSERT_QUERY_FORMAT = "insert into %s (%s) values (%s)";
	
	private String m_table;
	private String m_whereClause;
	private List<ColumnToSubPath> m_columnToSubPathMappings;
	private boolean m_insertOnUpdate;
	private String m_readQuery;
	private String m_updateQuery;
	
	static class ColumnToSubPath {
		private final String m_column;
		private final String m_subPath;

		public ColumnToSubPath(String column, String subPath) {
			m_column = column;
			m_subPath = subPath;
		}

		public String getColumn() {
			return m_column;
		}

		public String getSubPath() {
			return m_subPath;
		}

		@Override
		public String toString() {
			return String.format("%s -> %s", m_column, m_subPath);
		}
	}
	
	private MultiColumnCollectionAssetVariableConfig()  {}
	public MultiColumnCollectionAssetVariableConfig(ElementLocation smcLoc, @Nullable Duration validPeriod,
													@Nullable String jdbcConfigKey, String table, String whereClause,
													boolean insertOnUpdate, List<ColumnToSubPath> columnToSubPathMapping)  {
		super(smcLoc, jdbcConfigKey, validPeriod);
		
		m_table = table;
		m_whereClause = whereClause;
		m_columnToSubPathMappings = columnToSubPathMapping;
		m_insertOnUpdate = insertOnUpdate;
	}
	
	public List<ColumnToSubPath> getColumnToSubpathMapping() {
        return m_columnToSubPathMappings;
    }
	
	public String getReadQuery() {
		if ( m_readQuery == null ) {
			var colCsv = FStream.from(m_columnToSubPathMappings).map(ColumnToSubPath::getColumn).join(", ");
			m_readQuery = String.format(READ_QUERY_FORMAT, colCsv, m_table, m_whereClause);
		}
		return m_readQuery;
	}
	
	public String getUpdateQuery() {
		if ( m_updateQuery == null ) {
			if ( m_insertOnUpdate ) {
				var colCsv = FStream.from(m_columnToSubPathMappings).map(ColumnToSubPath::getColumn).join(", ");
				var valueHolderCsv = FStream.range(0, m_columnToSubPathMappings.size()).map(i -> "?").join(", ");
				m_updateQuery = String.format(INSERT_QUERY_FORMAT, m_table, colCsv, valueHolderCsv);
			}
			else {
				String setClause = FStream.from(m_columnToSubPathMappings)
											.map(mapping -> String.format("%s = ?", mapping.getColumn()))
											.join(", ");
				m_updateQuery = String.format(UPDATE_QUERY_FORMAT, m_table, setClause, m_whereClause);
			}
		}
		return m_updateQuery;
	}

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}
	
	
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);

		gen.writeStringField(FIELD_TABLE, m_table);
		gen.writeStringField(FIELD_WHERE_CLAUSE, m_whereClause);
		gen.writeBooleanField("insertOnUpdate", m_insertOnUpdate);
		gen.writeArrayFieldStart(FIELD_COLUMNS);
		for ( ColumnToSubPath mapping : m_columnToSubPathMappings ) {
			gen.writeStartObject();
			gen.writeStringField(FIELD_COLUMN, mapping.getColumn());
			gen.writeStringField(FIELD_SUBPATH, mapping.getSubPath());
			gen.writeEndObject();
		}
		gen.writeEndArray();
	}

	public static MultiColumnCollectionAssetVariableConfig deserializeFields(JsonNode jnode) {
		MultiColumnCollectionAssetVariableConfig config = new MultiColumnCollectionAssetVariableConfig();
		config.loadFields(jnode);

		config.m_table = JacksonUtils.getStringField(jnode, FIELD_TABLE);
		config.m_whereClause = JacksonUtils.getStringField(jnode, FIELD_WHERE_CLAUSE);
		config.m_insertOnUpdate = JacksonUtils.getBooleanField(jnode, "insertOnUpdate", false);
		
		JsonNode colNode = JacksonUtils.getFieldOrNull(jnode, FIELD_COLUMNS);
		if ( colNode == null ) {
			String msg = String.format("Cannot find '%s' field in multi-column AssetVariable config, %s",
										FIELD_COLUMNS, jnode);
			throw new AssetVariableException(msg);
		}
		config.m_columnToSubPathMappings
							= FStream.from(colNode.elements())
									.map(fieldJnode -> {
										String column = JacksonUtils.getStringField(fieldJnode, FIELD_COLUMN);
										String subPath = JacksonUtils.getStringField(fieldJnode, FIELD_SUBPATH);
										return new ColumnToSubPath(column, subPath);
									})
									.toList();
		
		return config;
	}
}
