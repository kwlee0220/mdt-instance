package mdt.persistence.timeseries;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Utilities;
import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;
import utils.jdbc.JdbcUtils;
import utils.stream.FStream;

import mdt.model.ModelGenerationException;
import mdt.model.sm.value.MultiLanguagePropertyValue;
import mdt.model.timeseries.DefaultInternalSegment;
import mdt.model.timeseries.DefaultRecord;
import mdt.model.timeseries.DefaultRecords;
import mdt.model.timeseries.InternalSegment;
import mdt.model.timeseries.RecordMetadata;
import mdt.persistence.timeseries.TimeSeriesSubmodelConfig.TailConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JdbcTailInternalSegment extends DefaultInternalSegment implements InternalSegment {
	private static final Logger s_logger = LoggerFactory.getLogger(JdbcTailInternalSegment.class);
	
	private final RecordMetadata m_metadata;
	private final TimeSeriesSubmodelConfig m_tsConfig;

	public JdbcTailInternalSegment(RecordMetadata metadata, TimeSeriesSubmodelConfig tsConfig) {
		m_metadata = metadata;
		m_tsConfig = tsConfig;
		
		setIdShort("Tail");
		setSemanticId(InternalSegment.SEMANTIC_ID);
		
		setName(new MultiLanguagePropertyValue("en", "Tail"));
		setDescription(new MultiLanguagePropertyValue("en", "Tail Segment"));
	}
	
	public void loadAASModel() {
		TailConfig tailConfig = m_tsConfig.getTail();
		
		List<? extends DefaultRecord> recList;
		if ( tailConfig.getDuration() != null ) {
			recList = readTailRecordsByDuration(tailConfig.getDuration());
		}
		else if ( tailConfig.getLength() > 0 ) {
			recList = readTailRecordsByLength(tailConfig.getLength());
		}
		else {
			throw new ModelGenerationException("invalid tail config: " + tailConfig);
		}
		
		setRecords(new DefaultRecords(recList));
	}
	
	@Override
	public String toString() {
		List<? extends DefaultRecord> recList = getRecords().getRecordList();
		String periodStr;
		if ( recList.size() > 0 ) {
			Instant startTs = recList.get(0).getTimestamp();
			Instant endTs = recList.get(recList.size() - 1).getTimestamp();
			periodStr = String.format("[%s ~ %s] (%d)", startTs, endTs, recList.size());
		}
		else {
			periodStr = "empty";
		}
		
		return String.format("JdbcTailInternalSegment[table=%s, period=%s, schema=%s]",
								m_tsConfig.getTableName(), periodStr, m_metadata);
	}

	private static final String SQL_GET_TAIL_SEGMENT_BY_TIME = """
		SELECT ${columns}
		FROM ${tableName}
		WHERE timestamp >= NOW() - (?::INTERVAL)
		ORDER BY id ASC;
	""";
	private List<DefaultRecord> readTailRecordsByDuration(Duration duration) {
		String colsExpr = FStream.from(m_tsConfig.getParameterColumns())
									.map(pc -> pc.getColumn())
									.join(", ");
		
		String sql = Utilities.substributeString(SQL_GET_TAIL_SEGMENT_BY_TIME,
												Map.of("tableName", m_tsConfig.getTableName(),
														"columns", colsExpr));

		JdbcConfiguration jdbcConf = JdbcConfiguration.parseString(m_tsConfig.getEndpoint());
		JdbcProcessor jdbc = JdbcProcessor.create(jdbcConf);
		try ( Connection conn = jdbc.connect();
			PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, JdbcUtils.toPostgresInterval(duration));
			
			return JdbcUtils.fstream(pstmt.executeQuery(), JdbcUtils::toColumnObjectList)
							.zipWithIndex()
							.map(idxed -> new DefaultRecord("" + idxed.index(), m_metadata, idxed.value()))
							.toList();
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to load Segment: %s.Tail (duration=%s), cause=%s",
										m_tsConfig.getTableName(), duration, "" + e);
			throw new ModelGenerationException(msg);
		}
	}

	private static final String SQL_GET_TAIL_SEGMENT_BY_COUNT = """
		SELECT ${columns}
		FROM ${tableName}
		ORDER BY id DESC
		LIMIT ?;
	""";
	private List<DefaultRecord> readTailRecordsByLength(int length) {
		String colsExpr = FStream.from(m_tsConfig.getParameterColumns())
									.map(pc -> pc.getColumn())
									.join(", ");
		
		// length의 자릿수를 알아내기
		int width = String.valueOf(length).length();
		String zeroPaddingFormat = "rec%0" + width + "d";
		
		String sql = Utilities.substributeString(SQL_GET_TAIL_SEGMENT_BY_COUNT,
												Map.of("tableName", m_tsConfig.getTableName(),
														"columns", colsExpr));
		JdbcConfiguration jdbcConf = JdbcConfiguration.parseString(m_tsConfig.getEndpoint());
		JdbcProcessor jdbc = JdbcProcessor.create(jdbcConf);
		try ( Connection conn = jdbc.connect();
			PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, length);
			
			return readRecords(zeroPaddingFormat, pstmt.executeQuery());
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to load Segment: %s.Tail (length=%d), cause=%s",
										m_tsConfig.getTableName(), length, "" + e);
			throw new ModelGenerationException(msg);
		}
	}
	
	private List<DefaultRecord> readRecords(String zpFormat, ResultSet rset) throws SQLException {
		var rows
			= JdbcUtils.fstream(rset, JdbcUtils::toColumnObjectList)
						.map(rs -> {
							return FStream.from(m_metadata.getFieldAll())
											.zipWith(FStream.from(rs))
											.map(tup -> {
												return tup._1.getType().fromJdbcObject(tup._2);
											})
											.toList();
						})
						.toList();

		int length = rows.size();
		DefaultRecord[] records = new DefaultRecord[length];
		FStream.from(rows)
				.zipWithIndex()
				.forEach(idxed -> {
					List<Object> colValues = idxed.value();
					int idx = length - idxed.index() - 1;
					String idShort = String.format(zpFormat, idx);
					DefaultRecord record = new DefaultRecord(idShort, m_metadata, colValues);
					records[idx] = record;
				});
		
		return Arrays.asList(records);
	}
}
