package mdt.persistence.timeseries;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;

import com.google.common.collect.Lists;

import mdt.model.sm.data.Data;
import mdt.model.sm.entity.SMCollectionField;
import mdt.model.sm.entity.SubmodelEntity;
import mdt.model.timeseries.DefaultMetadata;
import mdt.model.timeseries.DefaultSegment;
import mdt.model.timeseries.DefaultSegments;
import mdt.model.timeseries.Metadata;
import mdt.model.timeseries.Segments;
import mdt.model.timeseries.TimeSeries;
import mdt.persistence.timeseries.TimeSeriesSubmodelConfig.TailConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JdbcTimeSeries extends SubmodelEntity implements TimeSeries {
	private static final String IDSHORT = "TimeSeries";
	
	@SMCollectionField(idShort="Metadata", adaptorClass=DefaultMetadata.class)
	private Metadata metadata;
	
	@SMCollectionField(idShort="Segments", adaptorClass=DefaultSegments.class)
	private Segments segments;
	
	private final TimeSeriesSubmodelConfig tsConfig;
	
	public JdbcTimeSeries(Metadata metadata, TimeSeriesSubmodelConfig tsConfig) {
		setIdShort(IDSHORT);
		setSemanticId(Data.SEMANTIC_ID_REFERENCE);
		
		this.metadata = metadata;
		this.tsConfig = tsConfig;
	}
	
	public JdbcTimeSeries(TimeSeriesSubmodelConfig tsConfig) {
		setIdShort(IDSHORT);
		setSemanticId(Data.SEMANTIC_ID_REFERENCE);
		this.tsConfig = tsConfig;
	}
	
	public Metadata getMetadata() {
		return this.metadata;
	}
	
	public void setMetadata(Metadata metadata) {
		this.metadata = metadata;
	}
	
	public Segments getSegments() {
		return segments;
	}
	
	public TimeSeriesSubmodelConfig getTimeSeriesSubmodelConfig() {
		return tsConfig;
	}

	@Override
	public void updateFromAasModel(Submodel model) {
		super.updateFromAasModel(model);
		
		List<DefaultSegment> segmentList = Lists.newArrayList();
		
		// FullRange segment 추가
		JdbcFullRangeLinkedSegment fullRange = new JdbcFullRangeLinkedSegment(metadata.getRecord(), tsConfig);
		fullRange.load();
		segmentList.add(fullRange);
		
		// Tail configuration이 있으면 Tail segment 추가
		TailConfig tailConfig = this.tsConfig.getTail();
		if ( tailConfig != null ) {
			JdbcTailInternalSegment tail = new JdbcTailInternalSegment(metadata.getRecord(), tsConfig);
			tail.loadAASModel();
			segmentList.add(tail);
		}
		this.segments = new DefaultSegments(segmentList);
	}
	
	@Override
	public String toString() {
		return String.format(getIdShort());
	}
}
