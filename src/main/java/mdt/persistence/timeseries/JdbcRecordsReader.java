package mdt.persistence.timeseries;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.jetbrains.annotations.Nullable;

import utils.Instants;
import utils.Tuple;
import utils.jdbc.JdbcProcessor;
import utils.stream.FStream;
import utils.stream.KeyValueFStream;

import mdt.aas.DataType;
import mdt.persistence.timeseries.TimeSeriesSubmodelConfig.ParameterColumn;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.ref.timeseries.TimeSeriesRange;
import mdt.model.sm.value.ElementCollectionValue;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.PropertyValue;
import mdt.model.timeseries.Metadata;
import mdt.model.timeseries.RecordMetadata.Field;
import mdt.model.timeseries.Records;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
class JdbcRecordsReader {
	private final Metadata m_tsMetadata;
	private final String m_tableName;
	private final JdbcProcessor m_jdbc;
	/** Metadata의 레코드 필드 순서대로, 각 필드와 이에 대응하는 DB 컬럼을 담는다. */
	private final LinkedHashMap<String,Match> m_matches;
	/** 타임스탬프 컬럼명. 레코드의 첫번째 필드에 대응하는 DB 컬럼이다. */
	private final String m_tsColName;

	JdbcRecordsReader(Metadata tsMetadata, TimeSeriesSubmodelConfig tsConfig) throws IOException {
		m_tsMetadata = tsMetadata;
		m_tableName = tsConfig.getTableName();
		m_matches = buildFieldColumnMatches(tsMetadata, tsConfig);
		if ( m_matches.isEmpty() ) {
			throw new IOException("No record field is defined: table=" + m_tableName);
		}
		// 레코드의 첫번째 필드를 타임스탬프 필드로 간주한다.
		// ({@link mdt.model.timeseries.DefaultRecord#getTimestamp()}와 동일한 규약)
		m_tsColName = m_matches.values().iterator().next().dbColName();

		m_jdbc = JdbcProcessor.builderFromFullJdbcUrl(tsConfig.getEndpoint()).build();
	}

	/**
	 * Metadata의 각 레코드 필드에 대응하는 DB 컬럼을 시계열 설정에서 찾아 매핑을 구성한다.
	 * <p>
	 * 필드 이름({@code Metadata}의 {@code Record} 필드 idShort)과 설정의
	 * {@code parameterColumns[].parameter}를 이름으로 매칭한다.
	 *
	 * @param tsMetadata	대상 시계열의 Metadata.
	 * @param tsConfig		대상 시계열 Submodel의 설정.
	 * @return	필드 이름 → {@link Match} 매핑. Metadata의 필드 순서를 유지한다.
	 * @throws IOException	대응하는 컬럼이 설정에 정의되지 않은 필드가 있는 경우.
	 */
	private static LinkedHashMap<String,Match> buildFieldColumnMatches(Metadata tsMetadata,
																		TimeSeriesSubmodelConfig tsConfig)
		throws IOException {
		LinkedHashMap<String,String> paramToColumn = new LinkedHashMap<>();
		for ( ParameterColumn pc: tsConfig.getParameterColumns() ) {
			paramToColumn.put(pc.getParameter(), pc.getColumn());
		}

		LinkedHashMap<String,Match> matches = new LinkedHashMap<>();
		for ( Field field: tsMetadata.getRecord().getFieldAll() ) {
			String colName = paramToColumn.get(field.getName());
			if ( colName == null ) {
				String msg = String.format("Cannot find the column mapped to the record field '%s': "
											+ "table=%s, defined parameters=%s",
											field.getName(), tsConfig.getTableName(), paramToColumn.keySet());
				throw new IOException(msg);
			}
			matches.put(field.getName(), new Match(field, colName));
		}

		return matches;
	}

	/**
	 * 주어진 범위와 컬럼에 해당하는 레코드들을 읽어 {@code Records}
	 * {@link SubmodelElementCollection}으로 반환한다.
	 * <p>
	 * 각 레코드에는 조회 순서에 따라 {@code rec00}부터 시작하는 idShort가 부여된다.
	 *
	 * @param range		읽어올 레코드의 범위. {@code null}이면 전체 레코드를 읽는다.
	 * @param columns	읽어올 컬럼명 목록. {@code null}이거나 비어 있으면 전체 컬럼을 읽는다.
	 * @return	조회된 레코드들을 담은 {@link SubmodelElementCollection}.
	 * @throws IOException	질의 수행이나 레코드 변환에 실패한 경우.
	 */
	public SubmodelElementCollection read(@Nullable TimeSeriesRange range,
											@Nullable List<String> columns) throws IOException {
		Tuple<String,List<Field>> queryInfo = buildQuery(range, columns);
		String query = queryInfo._1;
		List<Field> fields = queryInfo._2;

		int idx = 0;
		List<SubmodelElement> records = new ArrayList<>();
		try ( ResultSet rs = m_jdbc.executeQuery(query, true) ) {
			while ( rs.next() ) {
				ElementCollectionValue colEmcv = toRecordValue(fields, rs);

				String idShort = String.format("rec%02d", idx++);
				records.add(toRecordSMC(idShort, colEmcv));
			}
			var recordsSmc = SubmodelUtils.newSubmodelElementCollection("Records", records);
			recordsSmc.setSemanticId(Records.SEMANTIC_ID_REFERENCE);
			return recordsSmc;
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to execute query for records: query=%s", query);
			throw new IOException(msg, e);
		}
	}

	/**
	 * 주어진 범위와 컬럼에 해당하는 레코드들을 읽어 값 객체로 반환한다.
	 *
	 * @param range		읽어올 레코드의 범위. {@code null}이면 전체 레코드를 읽는다.
	 * @param columns	읽어올 컬럼명 목록. {@code null}이거나 비어 있으면 전체 컬럼을 읽는다.
	 * @return	조회된 레코드들의 값.
	 * @throws IOException	질의 수행이나 레코드 변환에 실패한 경우.
	 */
	public ElementCollectionValue readValue(@Nullable TimeSeriesRange range,
											@Nullable List<String> columns) throws IOException {
		Tuple<String,List<Field>> queryInfo = buildQuery(range, columns);
		String query = queryInfo._1;
		List<Field> fields = queryInfo._2;

		int idx = 0;
		LinkedHashMap<String,ElementValue> records = new LinkedHashMap<>();
		try ( ResultSet rs = m_jdbc.executeQuery(query, true) ) {
			while ( rs.next() ) {
				ElementCollectionValue colEmcv = toRecordValue(fields, rs);

				String idShort = String.format("rec%02d", idx++);
				records.put(idShort, colEmcv);
			}
			return new ElementCollectionValue(records);
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to execute query for records: query=%s", query);
			throw new IOException(msg, e);
		}
	}

	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), m_tableName);
	}
	
	private SubmodelElementCollection toRecordSMC(String recIdShort, ElementCollectionValue colEmcv) {
		List<SubmodelElement> elements = KeyValueFStream.from(colEmcv.getFieldMap())
												.map(kv -> ((PropertyValue<?>)kv.value()).toElementBuilder()
																					.idShort(kv.key())
																					.build())
												.cast(SubmodelElement.class)
												.toList();
		var recordSmc = SubmodelUtils.newSubmodelElementCollection(recIdShort, elements);
		recordSmc.setSemanticId(mdt.model.timeseries.Record.SEMANTIC_ID_REFERENCE);
		return recordSmc;
	}
	
	private ElementCollectionValue toRecordValue(List<Field> fields, ResultSet rs) throws IOException {
		try {
			LinkedHashMap<String,ElementValue> cols = new LinkedHashMap<>();
			for ( int i = 0; i < fields.size(); i++ ) {
				Field field = fields.get(i);
				DataType<?> dtype = field.getType();

				Object obj = readColumnFromResultSet(rs, i+1, dtype);
				PropertyValue<?> col = PropertyValue.fromValueObject(obj, dtype.getTypeDefXsd());
				cols.put(field.getName(), col);
			}
			
			return new ElementCollectionValue(cols);
		}
		catch ( SQLException e ) {
			throw new IOException("Failed to read record from ResultSet", e);
		}
	}

	public static Object readColumnFromResultSet(ResultSet rs, int colIdx, DataType<?> dtype)
		throws SQLException {
		Object raw = ( dtype.getTypeDefXsd() == DataTypeDefXsd.DATE_TIME )
					? rs.getObject(colIdx, OffsetDateTime.class)
					: rs.getObject(colIdx);
		return dtype.fromJdbcObject(raw);
	}

	private Tuple<String,List<Field>> buildQuery(@Nullable TimeSeriesRange range,
												@Nullable List<String> columns) throws IOException {
		// 'columns' 정보를 이용하여 projection을 구성한다.
		Tuple<String,List<Field>> select = buildColumnList(columns);

		// 'range' 정보를 WHERE 절과 LIMIT 절로 나누어 구성한다.
		String whereClause = buildWhereClause(range);
		String limitClause = buildLimitClause(range);

		// 시계열 레코드는 항상 시각 오름차순으로 반환한다. LIMIT은 정렬 이후에 적용되어야 하므로
		// ORDER BY 뒤에 붙인다.
		String query = String.format("SELECT %s FROM %s%s ORDER BY %s ASC%s",
									select._1, m_tableName, whereClause, m_tsColName, limitClause);
		return Tuple.of(query, select._2);
	}


	private static record Match(Field field, String dbColName) { };
	private Tuple<String,List<Field>> buildColumnList(@Nullable List<String> columns) {
		// 컬럼이 지정되지 않은 경우(빈 목록 포함)는 전체 컬럼을 선택한다.
		Collection<Match> selecteds;
		if ( columns == null || columns.isEmpty() ) {
			selecteds = m_matches.values();
		}
		else {
			List<Match> matches = new ArrayList<>(columns.size());
			for ( String column: columns ) {
				Match match = m_matches.get(column);
				if ( match == null ) {
					String msg = String.format("Column not found in record metadata: column=%s, fields=%s",
												column, m_matches.keySet());
					throw new IllegalArgumentException(msg);
				}
				matches.add(match);
			}
			selecteds = matches;
		}

		// SELECT 절의 컬럼 순서와 필드 순서가 같아야 ResultSet을 위치로 읽을 수 있다.
		String clause = FStream.from(selecteds).map(Match::dbColName).join(", ");
		List<Field> selectedFields = FStream.from(selecteds).map(Match::field).toList();
		return Tuple.of(clause, selectedFields);
	}
	
	/**
	 * 주어진 범위에 해당하는 WHERE 절을 생성한다.
	 * <p>
	 * DB 컬럼에는 UTC 기준 값이 저장되므로 비교에 사용하는 시각 리터럴도 모두 UTC로 생성한다.
	 * 레코드 개수로 제한되는 범위({@code Count}/{@code Since})의 LIMIT 절은
	 * {@link #buildLimitClause(TimeSeriesRange)}가 담당한다.
	 *
	 * @param range	대상 조회 범위. {@code null}이면 빈 문자열을 반환한다.
	 * @return	생성된 WHERE 절. 조건이 없으면 빈 문자열.
	 * @throws IOException	마지막 레코드 시각 조회에 실패한 경우.
	 */
	private String buildWhereClause(@Nullable TimeSeriesRange range) throws IOException {
		if ( range == null || range instanceof TimeSeriesRange.Count ) {
			// Count는 조건 없이 정렬 결과의 마지막 length개를 취하므로 WHERE 절이 필요없다.
			return "";
		}
		else if ( range instanceof TimeSeriesRange.Trailing trailing ) {
			// NOW: 현재 시각 기준, LATEST: 가장 마지막 record의 timestamp 값을 기준으로 시작 시각을 계산한다.
			Instant start = (trailing.anchor() == TimeSeriesRange.Anchor.NOW)
							? Instant.now().minus(trailing.duration())
							: getLastTimestamp().minus(trailing.duration());
			return String.format(" WHERE %s >= '%s'", m_tsColName, Instants.toUTCString(start));
		}
		else if ( range instanceof TimeSeriesRange.Absolute absolute ) {
			List<String> conds = new ArrayList<>();
			if ( absolute.from() != null ) {
				conds.add(String.format("%s >= '%s'", m_tsColName, Instants.toUTCString(absolute.from())));
			}
			if ( absolute.to() != null ) {
				conds.add(String.format("%s <= '%s'", m_tsColName, Instants.toUTCString(absolute.to())));
			}
			return " WHERE " + String.join(" AND ", conds);
		}
		else if ( range instanceof TimeSeriesRange.Since since ) {
			return String.format(" WHERE %s >= '%s'", m_tsColName, Instants.toUTCString(since.from()));
		}
		else {
			throw new IllegalStateException("Invalid range: " + range);
		}
	}

	/**
	 * 주어진 범위에 해당하는 LIMIT 절을 생성한다.
	 * <p>
	 * {@code Count}는 시각 오름차순 정렬 결과의 <b>마지막</b> length개를 취해야 하므로 OFFSET을 함께
	 * 사용하고, {@code Since}는 시작 시각 이후 <b>처음</b> length개를 취하므로 LIMIT만 사용한다.
	 *
	 * @param range	대상 조회 범위. {@code null}이면 빈 문자열을 반환한다.
	 * @return	생성된 LIMIT 절. 개수 제한이 없으면 빈 문자열.
	 * @throws IOException	전체 레코드 개수 조회에 실패한 경우.
	 */
	private String buildLimitClause(@Nullable TimeSeriesRange range) throws IOException {
		if ( range instanceof TimeSeriesRange.Count count ) {
			try {
				long recCount = m_jdbc.rowCount(m_tableName);
				long offset = Math.max(0, recCount - count.length());
				return String.format(" LIMIT %d OFFSET %d", count.length(), offset);
			}
			catch ( SQLException e ) {
				throw new IOException("Failed to query record count for range-based query: table=" + m_tableName, e);
			}
		}
		else if ( range instanceof TimeSeriesRange.Since since ) {
			return String.format(" LIMIT %d", since.length());
		}
		else {
			return "";
		}
	}

	/**
	 * 원본 쿼리 결과에서 가장 마지막 record의 timestamp 값을 조회한다.
	 * <p>
	 * {@link TimeSeriesRange#last(java.time.Duration)} 기반 조회에서, 현재 시각이 아니라
	 * 실제로 저장된 마지막 record의 시각을 기준으로 시작 시각을 계산하기 위해 사용한다.
	 */
	private Instant getLastTimestamp() throws IOException {
		String query = String.format("SELECT MAX(%s) FROM %s", m_tsColName, m_tableName);
		try ( ResultSet rs = m_jdbc.executeQuery(query, true) ) {
			// timestamp는 record의 첫번째 필드이므로, 해당 필드의 DataType으로 변환한다.
			Field tsField = m_tsMetadata.getRecord().getFieldAll().get(0);

			Object obj = rs.next() ? readColumnFromResultSet(rs, 1, tsField.getType()) : null;
			if ( obj == null ) {
				throw new IOException("Cannot find the last record's timestamp: table=" + m_tableName);
			}
			return (Instant)obj;
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to query the last record's timestamp: query=%s", query);
			throw new IOException(msg, e);
		}
	}
}
