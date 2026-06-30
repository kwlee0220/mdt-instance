package mdt.persistence.timeseries;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.jetbrains.annotations.Nullable;

import utils.Instants;
import utils.LocalDateTimes;
import utils.Tuple;
import utils.jdbc.JdbcProcessor;
import utils.stream.FStream;
import utils.stream.KeyValueFStream;

import mdt.aas.DataType;
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

	@Nullable private TimeSeriesRange m_range;
	@Nullable private List<String> m_columns;
	
	JdbcRecordsReader(Metadata tsMetadata, String endpoint, String tableName) throws IOException {
		m_tsMetadata = tsMetadata;
		m_tableName = tableName;
		
		m_jdbc = JdbcProcessor.builderFromFullJdbcUrl(endpoint).build();
	}
	
	public void setColumns(List<String> columns) {
		m_columns = columns;
	}
	
	public void setRange(TimeSeriesRange range) {
		m_range = range;
	}

	public SubmodelElementCollection read() throws IOException {
		// 'm_columns' 정보와 'm_range' 정보를 이용하여 쿼리를 구성한다.
		Tuple<String,List<Field>> queryInfo = buildQuery();
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
	
	public ElementCollectionValue readValue() throws IOException {
		// 'm_columns' 정보와 'm_range' 정보를 이용하여 쿼리를 구성한다.
		Tuple<String,List<Field>> queryInfo = buildQuery();
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
		String colsStr = (m_columns != null) ? FStream.from(m_columns).join(",", "{", "}") : "";
		return String.format("%s[%s]%s", m_tableName, m_range, colsStr);
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

	private Tuple<String,List<Field>> buildQuery() throws IOException {
		// 'm_columns' 정보를 이용하여 projection을 구성한다.
		Tuple<String,List<Field>> select = buildColumnList();
		
		// 'm_range' 정보를 이용하여 WHERE 절을 구성한다.
		String whereClause = buildWhereClause();
		
		String query = String.format("SELECT %s FROM %s%s", select._1, m_tableName, whereClause);
		return Tuple.of(query, select._2);
	}

	
	private static record Match(Field field, String dbColName) { };
	private Tuple<String,List<Field>> buildColumnList() throws IOException {
		FStream<String> colNames;
		try {
			colNames = FStream.from(m_jdbc.getColumnInfos(m_tableName).keySet())
								.dropWhile(colName -> !colName.equalsIgnoreCase("timestamp"));
		}
		catch ( SQLException e ) {
			throw new IOException("Failed to query column information for table: " + m_tableName, e);
		}
		if ( m_columns == null ) {
			String clause = colNames.join(", ");
			return Tuple.of(clause, m_tsMetadata.getRecord().getFieldAll());
		}
		else {
			LinkedHashMap<String,Match> matchMap = FStream.from(m_tsMetadata.getRecord().getFieldAll())
															.zipWith(colNames)
															.map(pair -> new Match(pair._1, pair._2))
															.tagKey(m -> m.field().getName())
															.toMap(new LinkedHashMap<>());
			List<Match> matches =  FStream.from(m_columns)
											.lookup(matchMap)
											.mapOrThrow(kv -> {
												if ( kv.value() == null ) {
													throw new IllegalArgumentException("Column not found in record metadata: column="
																						+ kv.value());
												}
												return kv.value();
											})
											.toList();
			String clause = FStream.from(matches)
									.map(Match::dbColName)
									.join(", ");
			List<Field> selectedFields = FStream.from(matches)
												.map(Match::field)
												.toList();
			return Tuple.of(clause, selectedFields);
		}
	}
	
	private String buildWhereClause() throws IOException {
		// 'm_range' 정보를 WHERE 절로 구성한다.
		// (length 기반 LIMIT은 subquery로 감싼 뒤 적용하므로 아래에서 별도 처리한다.)
		if ( m_range != null ) {
			if ( m_range instanceof TimeSeriesRange.Count c ) {
				try {
					long recCount = m_jdbc.rowCount(m_tableName);
					long offset = Math.max(0, recCount - c.length());
					return String.format(" LIMIT %d OFFSET %d", c.length(), offset);
				}
				catch ( SQLException e ) {
					throw new IOException("Failed to query record count for range-based query: table=" + m_tableName, e);
				}
			}
			else if ( m_range instanceof TimeSeriesRange.Trailing trailing ) {
				// NOW: 현재 시각 기준, LATEST: 가장 마지막 record의 timestamp 값을 기준으로 시작 시각을 계산한다.
				Instant start = (trailing.anchor() == TimeSeriesRange.Anchor.NOW)
								? Instant.now().minus(trailing.duration())
								: getLastTimestamp().minus(trailing.duration());
				return String.format(" WHERE timestamp >= '%s'", Instants.toUTCString(start));
			}
			else if ( m_range instanceof TimeSeriesRange.Absolute absolute ) {
				List<String> conds = new ArrayList<>();
				if ( absolute.from() != null ) {
					String fromStr = LocalDateTimes.fromInstant(absolute.from()).toString();
					conds.add(String.format("timestamp >= '%s'", fromStr));
				}
				if ( absolute.to() != null ) {
					String toStr = LocalDateTimes.fromInstant(absolute.to()).toString();
					conds.add(String.format("timestamp <= '%s'", toStr));
				}
				return " WHERE " + String.join(" AND ", conds);
			}
			else {
				throw new IllegalStateException("Invalid range: " + m_range);
			}
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
		String query = String.format("SELECT MAX(timestamp) FROM %s", m_tableName);
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
