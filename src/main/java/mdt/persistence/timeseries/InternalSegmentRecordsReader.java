package mdt.persistence.timeseries;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Iterables;

import utils.stream.FStream;

import mdt.aas.DataTypes;
import mdt.model.sm.ref.DefaultElementReference;
import mdt.model.sm.ref.timeseries.TimeSeriesRange;
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
		else if ( range instanceof TimeSeriesRange.Since since ) {
			return readSince(records, since.from(), since.length());
		}
		else {
			throw new IllegalStateException("Invalid range: " + range);
		}
	}

	// 'from' 시각(포함)부터 시간 오름차순으로 length개 레코드를 남긴다.
	private SubmodelElementCollection readSince(SubmodelElementCollection records, Instant from, int length)
		throws IOException {
		records = listAfterOrEqual(records, from);

		List<SubmodelElement> recList = records.getValue();
		while ( recList.size() > length ) {
			recList.remove(recList.size() - 1);
		}

		return records;
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
}
