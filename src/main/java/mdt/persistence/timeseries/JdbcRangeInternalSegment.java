package mdt.persistence.timeseries;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Range;

import utils.Utilities;
import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;
import utils.jdbc.JdbcUtils;
import utils.stream.FStream;

import mdt.aas.DataTypes;
import mdt.model.ModelGenerationException;
import mdt.model.sm.value.MultiLanguagePropertyValue;
import mdt.model.timeseries.DefaultInternalSegment;
import mdt.model.timeseries.DefaultRecord;
import mdt.model.timeseries.DefaultRecords;
import mdt.model.timeseries.InternalSegment;
import mdt.model.timeseries.RecordMetadata;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JdbcRangeInternalSegment extends DefaultInternalSegment implements InternalSegment {
	private final RecordMetadata m_metadata;
	private final TimeSeriesSubmodelConfig m_tsConfig;
	private final Range m_range;

	public JdbcRangeInternalSegment(RecordMetadata metadata, TimeSeriesSubmodelConfig tsConfig, Range range) {
		m_metadata = metadata;
		m_tsConfig = tsConfig;
		m_range = range;
		
		setIdShort("ReadRecords");
		setSemanticId(InternalSegment.SEMANTIC_ID);
		
		setName(new MultiLanguagePropertyValue("en", "ReadRecords"));
		
		String desc = String.format("Records with the given Range: %s:%s", range.getMin(), range.getMax());
		setDescription(new MultiLanguagePropertyValue("en", desc));
	}
	
	public void loadAASModel() {
		List<? extends DefaultRecord> recList = readRecordsInRange(m_range);
		setRecords(new DefaultRecords(recList));
	}

	private static final String SQL_READ_RECORDS_IN_RANGE = """
		SELECT ${columns}
		FROM ${tableName}
		WHERE timestamp between ? and ?
		ORDER BY timestamp ASC;
	""";
	private static final String SQL_READ_RECORDS_LATER_THAN = """
		SELECT ${columns}
		FROM ${tableName}
		WHERE timestamp >= ?
		ORDER BY timestamp ASC;
	""";
	private static final String SQL_READ_RECORDS_EARLIER_THAN = """
		SELECT ${columns}
		FROM ${tableName}
		WHERE timestamp <= ?
		ORDER BY timestamp ASC;
	""";
	private List<DefaultRecord> readRecordsInRange(Range range) {
		JdbcConfiguration jdbcConf = JdbcConfiguration.parseString(m_tsConfig.getEndpoint());
		JdbcProcessor jdbc = JdbcProcessor.create(jdbcConf);

		try ( Connection conn = jdbc.connect();
			PreparedStatement pstmt = prepare(conn, range)) {
			return JdbcUtils.fstream(pstmt.executeQuery(), JdbcUtils::toColumnObjectList)
							.zipWithIndex()
							.map(idxed -> new DefaultRecord("" + idxed.index(), m_metadata, idxed.value()))
							.toList();
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read records: %s %s - %s, cause=%s",
										m_tsConfig.getTableName(), range.getMin(), range.getMax(), "" + e);
			throw new ModelGenerationException(msg);
		}
	}
		
	private PreparedStatement prepare(Connection conn, Range range) throws SQLException {
		Timestamp minTs = toSqlTimestamp(range.getMin());
		Timestamp maxTs = toSqlTimestamp(range.getMax());
		
		String colsExpr = FStream.from(m_tsConfig.getParameterColumns())
									.map(pc -> pc.getColumn())
									.join(", ");
		
		if ( minTs != null && maxTs != null ) {
			String sql = Utilities.substributeString(SQL_READ_RECORDS_IN_RANGE,
													Map.of("tableName", m_tsConfig.getTableName(),
															"columns", colsExpr));
			PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setTimestamp(1, minTs);
			pstmt.setTimestamp(2, maxTs);
			return pstmt;
		}
		else if ( minTs != null && maxTs == null ) {
			String sql = Utilities.substributeString(SQL_READ_RECORDS_LATER_THAN,
													Map.of("tableName", m_tsConfig.getTableName(),
															"columns", colsExpr));
			PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setTimestamp(1, minTs);
			return pstmt;
		}
		else if ( minTs == null && maxTs != null ) {
			String sql = Utilities.substributeString(SQL_READ_RECORDS_EARLIER_THAN,
													Map.of("tableName", m_tsConfig.getTableName(), "columns", colsExpr));
			PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setTimestamp(1, maxTs);
			return pstmt;
		}
		else {
			throw new ModelGenerationException("invalid range: " + range);
		}
	}
	
	private static Timestamp toSqlTimestamp(String aasTimestamp) {
		if ( aasTimestamp == null ) {
			return null;
		}
		else {
			Instant instant = DataTypes.DATE_TIME.parseValueString(aasTimestamp);
			return DataTypes.DATE_TIME.toJdbcObject(instant);
		}
	}
}
