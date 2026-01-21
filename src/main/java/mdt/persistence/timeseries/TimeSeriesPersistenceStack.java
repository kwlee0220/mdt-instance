package mdt.persistence.timeseries;

import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.Page;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.PagingInfo;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceAlreadyExistsException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotAContainerElementException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelElementSearchCriteria;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelSearchCriteria;

import utils.Throwables;
import utils.stream.FStream;

import mdt.model.sm.SubmodelUtils;
import mdt.model.timeseries.DefaultMetadata;
import mdt.model.timeseries.DefaultSegment;
import mdt.model.timeseries.DefaultSegments;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.PersistenceStack;
import mdt.persistence.timeseries.TimeSeriesSubmodelConfig.TailConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TimeSeriesPersistenceStack extends PersistenceStack<TimeSeriesPersistenceStackConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(TimeSeriesPersistenceStack.class);
	
	private TimeSeriesPersistenceStackConfig m_config;
	private Map<String,TimeSeriesSubmodelConfig> m_tsSubmodelConfigs;
	
	public TimeSeriesPersistenceStack() {
		setLogger(s_logger);
	}

	@Override
	public void init(CoreConfig coreConfig, TimeSeriesPersistenceStackConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		super.init(coreConfig, config, serviceContext);
		
		m_config = config;
		getLogger().info("initialized {}, config={}", this, config);
	}

	@Override
	public TimeSeriesPersistenceStackConfig asConfig() {
		return m_config;
	}

	@Override
	public void start() throws PersistenceException {
		super.start();
		
		// 시계열 Submodel과 관련된 시계열 설정 정보를 매핑시킨다.
		MDTModelLookup modelLookup = MDTModelLookup.getInstance();
		m_tsSubmodelConfigs = loadTimeSeriesSubmodelConfigs(modelLookup.getSubmodelAll());
	}

	@Override
	public Page<Submodel> findSubmodels(SubmodelSearchCriteria criteria, QueryModifier modifier, PagingInfo paging)
																						throws PersistenceException {
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
										catch ( PersistenceException e ) {
											Throwables.sneakyThrow(e);
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
	public Submodel getSubmodel(String id, QueryModifier modifier)
															throws ResourceNotFoundException, PersistenceException {
		Submodel baseModel = getBasePersistence().getSubmodel(id, modifier);
		
		TimeSeriesSubmodelConfig tsConfig = getTimeseriesSubmodelConfig(id);
		if ( tsConfig == null ) {
			return baseModel;
		}
		else {
			JdbcTimeSeries timeSeries = new JdbcTimeSeries(tsConfig);
			timeSeries.updateFromAasModel(baseModel);
			return timeSeries.newSubmodel();
		}
	}

	@Override
	public void deleteSubmodel(String id) throws ResourceNotFoundException, PersistenceException {
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
														PagingInfo paging)
															throws ResourceNotFoundException, PersistenceException {
		return getBasePersistence().findSubmodelElements(criteria, modifier, paging);
	}

	@Override
	public SubmodelElement getSubmodelElement(SubmodelElementIdentifier identifier, QueryModifier modifier)
															throws ResourceNotFoundException, PersistenceException {
		String submodelId = identifier.getSubmodelId();
		TimeSeriesSubmodelConfig tsConfig = getTimeseriesSubmodelConfig(submodelId);
		if ( tsConfig == null ) {
			SubmodelElement sme = getBasePersistence().getSubmodelElement(identifier, modifier);
			return sme;
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
											TimeSeriesSubmodelConfig tsConfig)
															throws ResourceNotFoundException, PersistenceException {
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
			JdbcTailInternalSegment segment = new JdbcTailInternalSegment(metadata.getRecord(), tsConfig);
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
			JdbcFullRangeLinkedSegment segment = new JdbcFullRangeLinkedSegment(metadata.getRecord(), tsConfig);
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
											throws ResourceNotFoundException, ResourceNotAContainerElementException,
													ResourceAlreadyExistsException, PersistenceException {
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
															throws ResourceNotFoundException, PersistenceException {
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
	public void deleteSubmodelElement(SubmodelElementIdentifier identifier)
															throws ResourceNotFoundException, PersistenceException {
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
	
	private Map<String,TimeSeriesSubmodelConfig> loadTimeSeriesSubmodelConfigs(Iterable<Submodel> submodels)
		throws PersistenceException {
		Map<String, TimeSeriesSubmodelConfig> tsConfigs = FStream.from(m_config.getTimeSeriesSubmodelConfigs())
																.tagKey(TimeSeriesSubmodelConfig::getIdShort)
																.toMap();
		Map<String,TimeSeriesSubmodelConfig> result = Maps.newHashMap();
		for ( Submodel sm : submodels ) {
			if ( !SubmodelUtils.isTimeSeriesSubmodel(sm) ) {
				continue;
			}
			
			TimeSeriesSubmodelConfig tsConfig = tsConfigs.remove(sm.getIdShort());
			if ( tsConfig == null ) {
				String msg = String.format("No TimeSeriesSubmodelConfig for TimeSeries submodel: idShort=%s",
											sm.getIdShort());
				throw new PersistenceException(msg);
			}
			result.put(sm.getId(), tsConfig);
		}
		for ( TimeSeriesSubmodelConfig unmatched : tsConfigs.values() ) {
			String msg = String.format("No TimeSeries submodel for TimeSeriesSubmodelConfig: idShort=%s",
										unmatched.getIdShort());
			throw new PersistenceException(msg);
		}
		
		return result;
	}
	
	private DefaultMetadata loadMetadataFromTimeSeries(String submodelId)
															throws ResourceNotFoundException, PersistenceException {
		// Metadata를 읽어 RecordSchema를 생성한다.
		SubmodelElementIdentifier metaId = new SubmodelElementIdentifier();
		metaId.setSubmodelId(submodelId);
		metaId.setIdShortPath(IdShortPath.parse("Metadata"));
		
		SubmodelElement metaSme = getBasePersistence().getSubmodelElement(metaId, QueryModifier.DEFAULT);
		DefaultMetadata metadata = new DefaultMetadata();
		metadata.updateFromAasModel(metaSme);
		
		return metadata;
	}

	@Override
	public void deleteAll() throws PersistenceException {
		getBasePersistence().deleteAll();
	}
}
