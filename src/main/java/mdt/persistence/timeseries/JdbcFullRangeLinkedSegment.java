package mdt.persistence.timeseries;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
public class JdbcFullRangeLinkedSegment extends DefaultLinkedSegment implements LinkedSegment {
	private static final Logger s_logger = LoggerFactory.getLogger(JdbcFullRangeLinkedSegment.class);
	
	private final TimeSeriesSubmodelConfig m_tsConfig;
	private final String m_query;

	public JdbcFullRangeLinkedSegment(RecordMetadata recordMetadata, TimeSeriesSubmodelConfig tsConfig) {
		m_tsConfig = tsConfig;
		
		setIdShort("FullRange");
		setSemanticId(LinkedSegment.SEMANTIC_ID);
		
		setName(new MultiLanguagePropertyValue("en", "FullRange"));
		setDescription(new MultiLanguagePropertyValue("en", "FullRange linked segment"));

		m_query = buildQuery(tsConfig);
	}
	
	/**
	 * 주어진 시계열 Submodel 설정에 해당하는 FullRange 세그먼트의 조회 질의를 생성한다.
	 *
	 * @param tsConfig	대상 시계열 Submodel의 설정.
	 * @return	전체 구간 조회 질의.
	 */
	static String buildQuery(TimeSeriesSubmodelConfig tsConfig) {
		String columnExpr = FStream.from(tsConfig.getParameterColumns())
									.map(pc -> pc.getColumn())
									.join(", ");
		return String.format("select %s from %s", columnExpr, tsConfig.getTableName());
	}
	
	public void load() {
		JdbcConfiguration jdbcConf = JdbcConfiguration.parseString(m_tsConfig.getEndpoint());
		JdbcProcessor jdbc = JdbcProcessor.builder(jdbcConf).build();

		// Endpoint는 Submodel 조회 응답으로 클라이언트에 그대로 전달되므로,
		// 설정의 endpoint에 포함된 사용자 이름과 비밀번호는 빼고 JDBC URL만 노출한다.
		setEndpoint(jdbcConf.getJdbcUrl());
		setQuery(m_query);

		// 레코드 수와 시간 범위를 하나의 집계 질의로 읽는다.
		// 이 메소드는 시계열 Submodel을 조회할 때마다 호출되므로, 연결은 try-with-resources로 반드시 닫는다.
		// (JdbcProcessor.executeQuery(sql, false)는 ResultSet을 닫아도 연결이 닫히지 않는다.)
		String SUMMARY_SQL = """
			SELECT count(*) as record_count, min(${tsCol}) as start_time, max(${tsCol}) as end_time
            FROM ${tableName}
		""";
		String summarySql = new StringSubstitutor(Map.of("tsCol", m_tsConfig.getTimestampColumn(),
														"tableName", m_tsConfig.getTableName()))
								.replace(SUMMARY_SQL);
		try ( Connection conn = jdbc.connect();
			  Statement stmt = conn.createStatement();
			  ResultSet rset = stmt.executeQuery(summarySql) ) {
			if ( !rset.next() ) {
				throw new ModelGenerationException("failed to count total timepoints");
			}

			setRecordCount(rset.getLong("record_count"));
			if ( getRecordCount() == 0 ) {
				// 레코드가 없으면 start_time과 end_time은 NULL이다.
				return;
			}

			setStartTime(rset.getTimestamp("start_time").toInstant());
			setEndTime(rset.getTimestamp("end_time").toInstant());
			setLastUpdate(getEndTime());
			setDuration(Duration.between(getStartTime(), getEndTime()));
			
			if ( s_logger.isInfoEnabled() ) {
				s_logger.info("loaded FullRangeLinkedSegment: {}[{}:{}]",
								m_tsConfig.getTableName(), getStartTime(), getEndTime());
			}
		}
		catch ( Throwable e ) {
			// ModelGenerationException에는 원인을 받는 생성자가 없으므로, 원인 스택이 사라지지 않도록 붙여 둔다.
			ModelGenerationException error
					= new ModelGenerationException("failed to load FullRange segment summary, cause=" + e);
			error.initCause(e);
			throw error;
		}
	}
	
	@Override
	public String toString() {
		// 비밀번호가 빠진 Endpoint 값을 출력한다(load() 이전에는 null).
		return String.format("JdbcFullRangeLinkedSegment(endpoint=%s, query=%s)", getEndpoint(), m_query);
	}
}
