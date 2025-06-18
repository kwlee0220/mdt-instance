package mdt.persistence.timeseries;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;

import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;
import utils.stream.FStream;

import mdt.model.ModelGenerationException;
import mdt.model.sm.value.MultiLanguagePropertyValue;
import mdt.model.timeseries.DefaultLinkedSegment;
import mdt.model.timeseries.LinkedSegment;
import mdt.model.timeseries.RecordMetadata;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JdbcWholeLinkedSegment extends DefaultLinkedSegment implements LinkedSegment {
	private static final Logger s_logger = LoggerFactory.getLogger(JdbcWholeLinkedSegment.class);
	
	private final TimeSeriesSubmodelConfig m_tsConfig;
	private final String m_query;

	public JdbcWholeLinkedSegment(RecordMetadata recordMetadata, TimeSeriesSubmodelConfig tsConfig) {
		m_tsConfig = tsConfig;
		
		setIdShort("Whole");
		setSemanticId(LinkedSegment.SEMANTIC_ID);
		
		setName(new MultiLanguagePropertyValue("en", "Whole"));
		setDescription(new MultiLanguagePropertyValue("en", "Whole linked segment"));

		String columnExpr = FStream.from(m_tsConfig.getParameterColumns())
									.map(pc -> pc.getColumn())
									.join(", ");
		m_query = String.format("select %s from %s", columnExpr, m_tsConfig.getTableName());
	}
	
	public void load() {
		setEndpoint(m_tsConfig.getEndpoint());
		setQuery(m_query);
		
		JdbcConfiguration jdbcConf = JdbcConfiguration.parseString(m_tsConfig.getEndpoint());
		JdbcProcessor jdbc = JdbcProcessor.create(jdbcConf);
		
		String cntSql = String.format("select count(*) from %s", m_tsConfig.getTableName());
		try ( ResultSet rset = jdbc.executeQuery(cntSql, false) ) {
			if ( !rset.next() ) {
				throw new ModelGenerationException("failed to count total timepoints");
			}
			
			setRecordCount(rset.getLong(1));
			if ( getRecordCount() == 0 ) {
				return;
			}
		}
		catch ( Throwable e ) {
			throw new ModelGenerationException("failed to count total timepoints, cause=" + e);
		}

		String MIN_MAX_SQL = """
			SELECT min(${tsCol}) as start_time, max(${tsCol}) as end_time
            FROM ${tableName}
		""";
		String minMaxSql = new StringSubstitutor(Map.of("tsCol", m_tsConfig.getTimestampColumn(),
														"tableName", m_tsConfig.getTableName()))
								.replace(MIN_MAX_SQL);
		try ( ResultSet rset = jdbc.executeQuery(minMaxSql, false) ) {
			if ( !rset.next() ) {
				throw new ModelGenerationException("failed to count total timepoints");
			}
			
			setStartTime(rset.getTimestamp("start_time").toInstant());
			setEndTime(rset.getTimestamp("end_time").toInstant());
			setLastUpdate(getEndTime());
			setDuration(Duration.between(getStartTime(), getEndTime()));
			
			if ( s_logger.isInfoEnabled() ) {
				s_logger.info("loaded WholeLinkedSegment: {}[{}:{}]",
								m_tsConfig.getTableName(), getStartTime(), getEndTime());
			}
		}
		catch ( Throwable e ) {
			throw new ModelGenerationException("failed to get min/max time, cause=" + e);
		}
	}
	
	@Override
	public String toString() {
		return String.format("JdbcWholeLinkedSegment(endpoint=%s, query=%s)", m_tsConfig.getEndpoint(), m_query);
	}
}
