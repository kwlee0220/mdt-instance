package mdt.persistence.timeseries;

import java.util.List;

import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.Setter;

import mdt.model.sm.data.Data;
import mdt.model.sm.entity.SMCollectionField;
import mdt.model.sm.entity.SubmodelEntity;
import mdt.model.timeseries.DefaultSegment;
import mdt.model.timeseries.DefaultSegments;
import mdt.model.timeseries.Metadata;
import mdt.model.timeseries.TimeSeries;
import mdt.persistence.timeseries.TimeSeriesSubmodelConfig.TailConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class JdbcTimeSeries extends SubmodelEntity implements TimeSeries {
	private static final String IDSHORT = "TimeSeries";
	
	@SMCollectionField(idShort="Metadata") private Metadata metadata;
	@SMCollectionField(idShort="Segments") private DefaultSegments segments;
	private final TimeSeriesSubmodelConfig tsConfig;
	
	public JdbcTimeSeries(Metadata metadata, TimeSeriesSubmodelConfig tsConfig) {
		setIdShort(IDSHORT);
		setSemanticId(Data.SEMANTIC_ID_REFERENCE);
		
		this.metadata = metadata;
		this.tsConfig = tsConfig;
	}
	
	public Metadata getMetadata() {
		return this.metadata;
	}
	
	public void loadAASModel() {
		List<DefaultSegment> segmentList = Lists.newArrayList();
		
		// FullRange segment 추가
		JdbcFullRangeLinkedSegment fullRange = new JdbcFullRangeLinkedSegment(this.metadata.getRecordMetadata(), this.tsConfig);
		fullRange.load();
		segmentList.add(fullRange);
		
		// Tail configuration이 있으면 Tail segment 추가
		TailConfig tailConfig = this.tsConfig.getTail();
		if ( tailConfig != null ) {
			JdbcTailInternalSegment tail = new JdbcTailInternalSegment(this.metadata.getRecordMetadata(), tsConfig);
			tail.loadAASModel();
			segmentList.add(tail);
		}
		segments = new DefaultSegments(segmentList);
	}
	
	@Override
	public String toString() {
		return String.format(getIdShort());
	}
}
