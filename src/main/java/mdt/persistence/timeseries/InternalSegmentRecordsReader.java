package mdt.persistence.timeseries;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Iterables;

import utils.Instants;
import utils.Tuple;
import utils.jdbc.JdbcProcessor;
import utils.stream.FStream;
import utils.stream.KeyValueFStream;

import mdt.aas.DataType;
import mdt.aas.DataTypes;
import mdt.model.sm.ref.DefaultElementReference;
import mdt.model.sm.ref.timeseries.TimeSeriesRange;
import mdt.model.sm.value.ElementCollectionValue;
import mdt.model.timeseries.DefaultRecord;
import mdt.model.timeseries.Metadata;
import mdt.model.timeseries.RecordMetadata.Field;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class InternalSegmentRecordsReader {
	private final Metadata m_tsMetadata;
	@Nullable private final TimeSeriesRange m_range;
	@Nullable private final List<String> m_columns;
	private final DefaultElementReference m_recordsRef;
	
	private String m_baseQuery;
	
	public InternalSegmentRecordsReader(Metadata tsMetadata, @Nullable TimeSeriesRange range,
										@Nullable List<String> columns,
										DefaultElementReference segmentRef) {
		m_tsMetadata = tsMetadata;
		m_range = range;
		m_columns = columns;
		m_recordsRef = segmentRef.child("Records");
	}
	
	protected SubmodelElementCollection read() throws IOException {
		SubmodelElementCollection records = m_recordsRef.readCollection();
		
		if ( m_range != null ) {
			records = readByRange(records, m_range);
		}
		if ( m_columns != null ) {
			records = project(records);
		}
		return records;
	}

	private SubmodelElementCollection project(SubmodelElementCollection records) throws IOException {
		Map<String,Integer> fieldMap = FStream.from(m_tsMetadata.getRecord().getFieldAll())
											.zipWithIndex()
											.toKeyValueStream(idxed -> idxed.value().getName(), idxed -> idxed.index())
											.toMap(new LinkedHashMap<>());
		List<Integer> colIdxList = FStream.from(m_columns)
											.lookup(fieldMap, true)
											.mapOrThrow(kv -> {
												if ( kv.value() == null ) {
													String msg = String.format("Not found column=%s in record metadata", kv.key());
													throw new IOException(msg);
												}
												return kv.value();
											})
											.toList();
		for ( SubmodelElement recSme: records.getValue() ) {
			if ( recSme instanceof SubmodelElementCollection recSmc ) {
				List<SubmodelElement> colList = recSmc.getValue();
				List<SubmodelElement> projectedColList = new ArrayList<>(colIdxList.size());
				for ( int colIdx: colIdxList ) {
					projectedColList.add(colList.get(colIdx));
				}
				colList.clear();
				colList.addAll(projectedColList);
			}
			else {
				String msg = String.format("Record is not a SubmodelElementCollection: %s",
											recSme.getClass().getName());
				throw new IOException(msg);
			}
		}
		
		return records;
	}
	
	private SubmodelElementCollection readByRange(SubmodelElementCollection records,
													TimeSeriesRange range) throws IOException {
		if ( range instanceof TimeSeriesRange.Count count ) {
			return readByLength(records, count.length());
		}
		else if ( range instanceof TimeSeriesRange.Trailing trailing ) {
			return (trailing.anchor() == TimeSeriesRange.Anchor.NOW)
					? readByDuration(records, trailing.duration())		// 현재 시각 기준
					: readByLast(records, trailing.duration());			// 마지막 레코드 시각 기준
		}
		else if ( range instanceof TimeSeriesRange.Absolute absolute ) {
			return listBetween(records, absolute.from(), absolute.to());
		}
		else {
			throw new IllegalStateException("Invalid range: " + range);
		}
	}
	
	private SubmodelElementCollection readByLength(SubmodelElementCollection records, int length)
		throws IOException {
		int recCount = records.getValue().size();
		if ( recCount > length ) {
			List<SubmodelElement> recList = records.getValue();
			int skipCount = recCount - length;
			for ( int i = 0; i < skipCount; i++ ) {
				recList.remove(0);
			}
		}
		
		return records;
	}
	private ElementCollectionValue readByLength(ElementCollectionValue records, int length)
		throws IOException {
		int recCount = records.size();
		if ( recCount > length ) {
			var reduced = KeyValueFStream.from(records.getFieldMap())
										.drop(recCount - length)
										.toKeyValueStream(kv -> kv)
										.toMap(new LinkedHashMap<>());
			records = new ElementCollectionValue(reduced);
		}
		
		return records;
	}
	
	private SubmodelElementCollection readByDuration(SubmodelElementCollection smec, @NotNull Duration duration)
		throws IOException {
		Instant startTs = Instant.now().minus(duration);
		return listAfterOrEqual(smec, startTs);
	}
	
	private SubmodelElementCollection readByLast(SubmodelElementCollection smec, @NotNull Duration duration)
		throws IOException {
		if ( smec.getValue().size() <= 1 ) {
			return smec;
		}
		
		var lastSmc = (SubmodelElementCollection)Iterables.getLast(smec.getValue());
		var tsProp = (Property)lastSmc.getValue().get(0);
		Instant lastTs = DataTypes.DATE_TIME.parseValueString(tsProp.getValue());
		Instant start = lastTs.minus(duration);
		return listAfterOrEqual(smec, start);
	}

	// 절대 시간 범위 [from, to] 필터. from/to 중 하나가 null이면 해당 경계는 개방.
	private SubmodelElementCollection listBetween(SubmodelElementCollection smec,
													@Nullable Instant from, @Nullable Instant to) throws IOException {
		DefaultRecord recHandle = new DefaultRecord(m_tsMetadata.getRecord());
		for ( Iterator<SubmodelElement> iter = smec.getValue().iterator(); iter.hasNext(); ) {
			SubmodelElement rec = iter.next();
			recHandle.updateFromAasModel(rec);
			Instant ts = recHandle.getTimestamp();

			if ( (from != null && ts.isBefore(from)) || (to != null && ts.isAfter(to)) ) {
				iter.remove();
			}
		}

		return smec;
	}

	private SubmodelElementCollection listAfterOrEqual(SubmodelElementCollection smec, Instant start)
		throws IOException {
		DefaultRecord recHandle = new DefaultRecord(m_tsMetadata.getRecord());
		for ( Iterator<SubmodelElement> iter = smec.getValue().iterator(); iter.hasNext(); ) {
			SubmodelElement rec = iter.next();
			recHandle.updateFromAasModel(rec);
			Instant ts = recHandle.getTimestamp();
			
			if ( ts.isBefore(start) ) {
				iter.remove();
			}
			else {
				break;
			}
		}
		
		return smec;
	}
	
	private Tuple<String, List<Field>> buildQuery(JdbcProcessor jdbc) throws IOException {
		// 'm_columns'에 명시된 컬럼명과 매칭되는 필드명을 찾아 projection을 구성한다.
		String selectClause = null;
		List<Field> projectedFields = m_tsMetadata.getRecord().getFieldAll();
		if ( m_columns != null ) {
			LinkedHashMap<String, Field> fieldMap = m_tsMetadata.getRecord().getFieldMap();
			projectedFields = FStream.from(m_columns)
									.lookup(fieldMap)
									.mapOrThrow(kv -> {
										if ( kv.value() == null ) {
											String msg = String.format("Not found column=%s in record metadata", kv.key());
											throw new IOException(msg);
										}
										return kv.value();
									})
									.toList();
			selectClause = FStream.from(projectedFields).map(Field::getName).join(", ");
		}

		// 'm_range' 정보를 WHERE 절로 구성한다.
		// (length 기반 LIMIT은 subquery로 감싼 뒤 적용하므로 아래에서 별도 처리한다.)
		String whereClause = "";
		if ( m_range != null ) {
			if ( m_range instanceof TimeSeriesRange.Count ) {
				// LIMIT/OFFSET은 subquery 래핑 이후에 적용한다.
				String.format(" FROM (%s) AS subquery ORDER BY timestamp ", m_baseQuery);
			}
			else if ( m_range instanceof TimeSeriesRange.Trailing trailing ) {
				// NOW: 현재 시각 기준, LATEST: 가장 마지막 record의 timestamp 값을 기준으로 시작 시각을 계산한다.
				Instant start = (trailing.anchor() == TimeSeriesRange.Anchor.NOW)
								? Instant.now().minus(trailing.duration())
								: getLastTimestamp(jdbc, m_baseQuery).minus(trailing.duration());
				whereClause = String.format(" WHERE timestamp >= '%s'", Instants.toUTCString(start));
			}
			else if ( m_range instanceof TimeSeriesRange.Absolute absolute ) {
				List<String> conds = new ArrayList<>();
				if ( absolute.from() != null ) {
					conds.add(String.format("timestamp >= '%s'", Instants.toUTCString(absolute.from())));
				}
				if ( absolute.to() != null ) {
					conds.add(String.format("timestamp <= '%s'", Instants.toUTCString(absolute.to())));
				}
				whereClause = " WHERE " + String.join(" AND ", conds);
			}
			else {
				throw new IllegalStateException("Invalid range: " + m_range);
			}
		}

		// 추가 가공이 필요없는 경우는 원본 쿼리를 그대로 사용한다.
		if ( m_columns == null && m_range == null ) {
			return Tuple.of(m_baseQuery, projectedFields);
		}

		// 원본 쿼리(baseQuery)는 임의의 SQL일 수 있어 (이미 WHERE/ORDER BY/LIMIT 등이 포함될 수 있음)
		// 절을 직접 이어붙이지 않고, 항상 subquery로 감싼 뒤 projection/WHERE/LIMIT를 적용한다.
		// (WHERE는 모든 원본 컬럼을 가진 subquery에 적용되므로 projection에서 빠진 'timestamp'로도 필터링 가능)
		String query = String.format("SELECT %s FROM (%s) AS subquery%s",
									selectClause, m_baseQuery, whereClause);
		
		// query 의 결과에서 마지막 record를 기준으로 LIMIT를 적용한다.
		// (length는 "가장 최근 length개 record"를 의미하므로, 전체 개수에서 length를 뺀
		//  위치부터 length개를 가져오도록 LIMIT/OFFSET을 구성한다.)
		if ( m_range instanceof TimeSeriesRange.Count count ) {
//			long recCount = (Long)getSegmentField("RecordCount");
			long recCount = 0;
			long offset = Math.max(0, recCount - count.length());
			query = String.format("%s LIMIT %d OFFSET %d", query, count.length(), offset);
		}

		return Tuple.of(query, projectedFields);
	}

	/**
	 * 원본 쿼리 결과에서 가장 마지막 record의 timestamp 값을 조회한다.
	 * <p>
	 * {@link TimeSeriesRange#last(java.time.Duration)} 기반 조회에서, 현재 시각이 아니라
	 * 실제로 저장된 마지막 record의 시각을 기준으로 시작 시각을 계산하기 위해 사용한다.
	 */
	private Instant getLastTimestamp(JdbcProcessor jdbc, String baseQuery) throws IOException {
		String query = String.format("SELECT MAX(timestamp) FROM (%s) AS subquery", baseQuery);
		try ( ResultSet rs = jdbc.executeQuery(query, true) ) {
			// timestamp는 record의 첫번째 필드이므로, 해당 필드의 DataType으로 변환한다.
			Field tsField = m_tsMetadata.getRecord().getFieldAll().get(0);

			Object obj = rs.next() ? readColumnFromResultSet(rs, 1, tsField.getType()) : null;
			if ( obj == null ) {
				throw new IOException("Cannot find the last record's timestamp: query=" + baseQuery);
			}
			return (Instant)obj;
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to query the last record's timestamp: query=%s", query);
			throw new IOException(msg, e);
		}
	}

	public static Object readColumnFromResultSet(ResultSet rs, int colIdx, DataType<?> dtype)
		throws SQLException {
		Object raw = ( dtype.getTypeDefXsd() == DataTypeDefXsd.DATE_TIME )
					? rs.getObject(colIdx, OffsetDateTime.class)
					: rs.getObject(colIdx);
		return dtype.fromJdbcObject(raw);
	}
}
