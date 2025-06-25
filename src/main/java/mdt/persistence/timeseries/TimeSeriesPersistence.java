package mdt.persistence.timeseries;

import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import utils.KeyValue;
import utils.stream.FStream;

import mdt.model.sm.SubmodelUtils;
import mdt.model.timeseries.DefaultMetadata;
import mdt.model.timeseries.DefaultSegment;
import mdt.model.timeseries.DefaultSegments;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.PersistenceStack;
import mdt.persistence.timeseries.TimeSeriesSubmodelConfig.TailConfig;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.Page;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.PagingInfo;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotAContainerElementException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelElementSearchCriteria;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelSearchCriteria;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TimeSeriesPersistence extends PersistenceStack<TimeSeriesPersistenceConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(TimeSeriesPersistence.class);
	
	private TimeSeriesPersistenceConfig m_config;
	private Map<String,TimeSeriesSubmodelConfig> m_tsSubmodelConfigs;
	private Map<String,Submodel> m_rawTimeSeriesSubmodels;
	
	public TimeSeriesPersistence() {
		setLogger(s_logger);
	}

	@Override
	public void init(CoreConfig coreConfig, TimeSeriesPersistenceConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		super.init(coreConfig, config, serviceContext);
		
		m_config = config;
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("initialized {}, config={}", this, config);
		}
		
		// 시계열 Submodel과 관련된 설정 정보를 찾는다.
		MDTModelLookup modelLookup = MDTModelLookup.getInstance();
		m_tsSubmodelConfigs = loadTimeSeriesSubmodelConfigs(modelLookup.getSubmodelAll());
	}

	@Override
	public TimeSeriesPersistenceConfig asConfig() {
		return m_config;
	}

	@Override
	public Page<Submodel> findSubmodels(SubmodelSearchCriteria criteria, QueryModifier modifier, PagingInfo paging) {
		Page<Submodel> paged = getBasePersistence().findSubmodels(criteria, modifier, paging);
		List<Submodel> newContent
						= FStream.from(paged.getContent())
						        .flatMapNullable(submodel -> {
									TimeSeriesSubmodelConfig tsConfig = getTimeseriesSubmodelConfig(submodel.getId());
									if ( tsConfig != null ) {
										try {
											Submodel sm = getSubmodel(submodel.getId(), modifier);
											return sm;
										}
										catch ( ResourceNotFoundException e ) {
											return null;
										}
									}
									else {
										return submodel;
									}
								})
								.toList();
		return Page.of(newContent, paged.getMetadata());
	}

	@Override
	public Submodel getSubmodel(String id, QueryModifier modifier) throws ResourceNotFoundException {
		Submodel baseModel = getBasePersistence().getSubmodel(id, modifier);
		
		TimeSeriesSubmodelConfig tsConfig = getTimeseriesSubmodelConfig(id);
		if ( tsConfig == null ) {
			return baseModel;
		}
		else {
			// Base Submodel에 포함된 Metadata를 읽어 RecordMetadata를 생성한다.
			SubmodelElementCollection metaSmc = SubmodelUtils.traverse(baseModel, "Metadata",
																		SubmodelElementCollection.class);
			DefaultMetadata metadata = new DefaultMetadata();
			metadata.updateFromAasModel(metaSmc);
			
			JdbcTimeSeries timeSeries = new JdbcTimeSeries(metadata, tsConfig);
			timeSeries.loadAASModel();
			return timeSeries.newSubmodel();
		}
	}

	@Override
	public void deleteSubmodel(String id) throws ResourceNotFoundException {
		TimeSeriesSubmodelConfig tsConfig = getTimeseriesSubmodelConfig(id);
		if ( tsConfig == null ) {
			getBasePersistence().deleteSubmodel(id);
		}
		else {
			throw new UnsupportedOperationException("delete() is not supported for TimeSeries submodel");
		}
	}

	@Override
	public Page<SubmodelElement> findSubmodelElements(SubmodelElementSearchCriteria criteria, QueryModifier modifier,
														PagingInfo paging) throws ResourceNotFoundException {
		return getBasePersistence().findSubmodelElements(criteria, modifier, paging);
	}

	@Override
	public SubmodelElement getSubmodelElement(SubmodelElementIdentifier identifier, QueryModifier modifier)
																				throws ResourceNotFoundException {
		String submodelId = identifier.getSubmodelId();
		TimeSeriesSubmodelConfig tsConfig = getTimeseriesSubmodelConfig(submodelId);
		if ( tsConfig == null ) {
			return getBasePersistence().getSubmodelElement(identifier, modifier);
		}
		
		IdShortPath idShortPath = identifier.getIdShortPath();
		List<String> parts = idShortPath.getElements();
		if ( parts.get(0).equals("Metadata") ) {
			return getBasePersistence().getSubmodelElement(identifier, modifier);
		}
		else if ( parts.get(0).equals("Segments") ) {
			return traverseSegments(submodelId, idShortPath, tsConfig);
		}
		
		return getBasePersistence().getSubmodelElement(identifier, modifier);
	}
	
	private SubmodelElement traverseSegments(String submodelId, IdShortPath idShortPath,
											TimeSeriesSubmodelConfig tsConfig) throws ResourceNotFoundException {
		// Metadata를 읽어 RecordSchema를 생성한다.
		DefaultMetadata metadata = loadMetadataFromTimeSeries(submodelId);
		
		List<DefaultSegment> segmentList = Lists.newArrayList();

		List<String> parts = idShortPath.getElements();
		String segmentId = (parts.size() > 1) ?  parts.get(1) : null;
		
		// 모든 segments를 포함(segmentId == null)하던가, 특정 segment만 포함(segmentId != null)
		if ( segmentId == null || segmentId.equals("Tail") ) {
			TailConfig tailConfig = tsConfig.getTail();
			if ( tailConfig == null ) {
				throw new mdt.model.ResourceNotFoundException("TailConfig", "path=" + idShortPath);
			}
			JdbcTailInternalSegment segment = new JdbcTailInternalSegment(metadata.getRecordMetadata(), tsConfig);
			segment.loadAASModel();
			if ( getLogger().isDebugEnabled() ) {
				getLogger().info("loaded: {}", segment);
			}
			
			if ( parts.size() > 1 ) {
				SubmodelElementCollection segmentSmc = segment.newSubmodelElement();
				String subPath = FStream.from(parts).drop(2).join('.');
				if ( subPath.length() > 0 ) {
					return SubmodelUtils.traverse(segmentSmc, subPath);
				}
				else {
					return segmentSmc;
				} 
			}
			segmentList.add(segment);
		}
		if ( segmentId == null || segmentId.equals("FullRange") ) {
			JdbcFullRangeLinkedSegment segment = new JdbcFullRangeLinkedSegment(metadata.getRecordMetadata(), tsConfig);
			segment.load();
			if ( getLogger().isDebugEnabled() ) {
				getLogger().info("loaded: {}", segment);
			}
			
			if ( parts.size() > 1 ) {
				SubmodelElementCollection segmentSmc = segment.newSubmodelElement();
				String subPath = FStream.from(parts).drop(2).join('.');
				if ( subPath.length() > 0 ) {
					return SubmodelUtils.traverse(segmentSmc, subPath);
				}
				else {
					return segmentSmc;
				} 
			}
			segmentList.add(segment);
		}

		DefaultSegments segments = new DefaultSegments(segmentList);
		if ( segmentId == null ) {
			return segments.newSubmodelElement();
		}
		
		SubmodelElementCollection segmentSmc = segmentList.get(0).newSubmodelElement();
		if ( parts.size() == 2 ) {
			return segmentSmc;
		}
		else {
			String subPath = FStream.from(parts).drop(2).join('/');
			return SubmodelUtils.traverse(segmentSmc, subPath);
		}
	}

	@Override
	public void insert(SubmodelElementIdentifier parentIdentifier, SubmodelElement submodelElement)
											throws ResourceNotFoundException, ResourceNotAContainerElementException {
		String submodelId = parentIdentifier.getSubmodelId();
		TimeSeriesSubmodelConfig tsConfig = getTimeseriesSubmodelConfig(submodelId);
		if ( tsConfig == null ) {
			getBasePersistence().insert(parentIdentifier, submodelElement);
		}
		else {
			throw new UnsupportedOperationException("insert() is not supported for TimeSeries submodel");
		}
	}

	@Override
	public void update(SubmodelElementIdentifier identifier, SubmodelElement submodelElement)
																				throws ResourceNotFoundException {
		String submodelId = identifier.getSubmodelId();
		TimeSeriesSubmodelConfig tsConfig = getTimeseriesSubmodelConfig(submodelId);
		if ( tsConfig == null ) {
			getBasePersistence().update(identifier, submodelElement);
		}
		else {
			throw new UnsupportedOperationException("update() is not supported for TimeSeries submodel");
		}
	}

	@Override
	public void deleteSubmodelElement(SubmodelElementIdentifier identifier) throws ResourceNotFoundException {
		String submodelId = identifier.getSubmodelId();
		TimeSeriesSubmodelConfig tsConfig = getTimeseriesSubmodelConfig(submodelId);
		if ( tsConfig == null ) {
			getBasePersistence().deleteSubmodelElement(identifier);
		}
		else {
			throw new UnsupportedOperationException("delete() is not supported for TimeSeries submodel");
		}
	}
	
	private TimeSeriesSubmodelConfig getTimeseriesSubmodelConfig(String submodelId) {
		return m_tsSubmodelConfigs.get(submodelId);
	}
	
	private Map<String,TimeSeriesSubmodelConfig> loadTimeSeriesSubmodelConfigs(Iterable<Submodel> submodels) {
		return FStream.from(submodels)
					    .tagKey(Submodel::getIdShort)
					    .innerJoin(FStream.from(m_config.getTimeSeriesSubmodelConfigs())
					    								.tagKey(TimeSeriesSubmodelConfig::getIdShort))
					    .mapKeyValue((idShort, tup) -> KeyValue.of(tup._1.getId(), tup._2))
					    .toMap();
	}
	
	private DefaultMetadata loadMetadataFromTimeSeries(String submodelId) throws ResourceNotFoundException {
		// Metadata를 읽어 RecordSchema를 생성한다.
		SubmodelElementIdentifier metaId = new SubmodelElementIdentifier();
		metaId.setSubmodelId(submodelId);
		metaId.setIdShortPath(IdShortPath.parse("Metadata"));
		
		SubmodelElement metaSme = getBasePersistence().getSubmodelElement(metaId, QueryModifier.DEFAULT);
		DefaultMetadata metadata = new DefaultMetadata();
		metadata.updateFromAasModel(metaSme);
		
		return metadata;
	}
}
