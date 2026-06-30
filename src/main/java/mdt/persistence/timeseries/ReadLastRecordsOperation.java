package mdt.persistence.timeseries;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Range;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;

import utils.Preconditions;
import utils.func.Funcs;
import utils.jdbc.JdbcUtils;
import utils.stream.FStream;

import mdt.aas.DataTypes;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.assetconnection.operation.OperationProvider;
import mdt.config.MDTService;
import mdt.model.ModelGenerationException;
import mdt.model.expr.MDTExpressionParser;
import mdt.model.sm.ref.timeseries.TimeSeriesRange;
import mdt.model.timeseries.DefaultLinkedSegment;
import mdt.model.timeseries.DefaultMetadata;
import mdt.model.timeseries.DefaultRecord;
import mdt.model.timeseries.DefaultRecords;
import mdt.model.timeseries.Metadata;
import mdt.model.timeseries.RecordMetadata;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ReadLastRecordsOperation implements OperationProvider {
	private final Reference m_opRef;
	private final JavaOperationProviderConfig m_config;
	private final TimeSeriesPersistenceStack m_persist;
	private final TimeSeriesSubmodelConfig m_tsConfig;
	private final JdbcRecordsReader m_reader;
	
	public ReadLastRecordsOperation(ServiceContext serviceContext, Reference operationRef,
										JavaOperationProviderConfig config)
																throws ConfigurationInitializationException {
		m_opRef = operationRef;
		m_config = config;
		
		m_persist = (TimeSeriesPersistenceStack)((MDTService)serviceContext).getPersistence();
		m_tsConfig = getTimeSeriesSubmodelConfig(m_persist, operationRef);

		try {
			Metadata metadata = readMetadata(m_persist, m_opRef);
			m_reader = new JdbcRecordsReader(metadata, m_tsConfig.getEndpoint(), m_tsConfig.getTableName());
		}
		catch ( IOException e ) {
			throw new ConfigurationInitializationException("failed to initialize JdbcRecordsReader", e);
		}
		catch ( PersistenceException e ) {
			throw new ConfigurationInitializationException("failed to read Metadata for JdbcRecordsReader", e);
		}
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		Map<String, SubmodelElement> inputVarList = FStream.of(inputVars)
															.map(OperationVariable::getValue)
															.tagKey(SubmodelElement::getIdShort)
															.toMap();
		
		SubmodelElement arg1 = inputVarList.get("Range");
		Preconditions.checkArgument(arg1 == null
				|| (arg1 instanceof Property && ((Property)arg1).getValueType() == DataTypeDefXsd.STRING),
									"Input argument 'Range' is not a Property");
		String rangeExpr = ((Property)arg1).getValue();
		if ( rangeExpr != null ) {
			TimeSeriesRange tsRange = MDTExpressionParser.parseTimeSeriesRange(rangeExpr).evaluate();
			m_reader.setRange(tsRange);
		}

		SubmodelElement arg2 = inputVarList.get("Columns");
		Preconditions.checkArgument(arg2 == null
				|| (arg2 instanceof Property && ((Property)arg2).getValueType() == DataTypeDefXsd.STRING),
									"Input argument 'Columns' is not a Property");
		String columnsCsv = ((Property)arg2).getValue();
		if ( columnsCsv != null ) {
			List<String> columns = FStream.of(columnsCsv.split(","))
										.map(String::trim)
										.filter(s -> !s.isEmpty())
										.toList();
			m_reader.setColumns(columns);
		}
		else {
			m_reader.setColumns(null);
		}
		
		SubmodelElementCollection records = m_reader.read();
		outputVars[0].setValue(records);
	}
	
	private static TimeSeriesSubmodelConfig getTimeSeriesSubmodelConfig(
										TimeSeriesPersistenceStack tsPersistStack, Reference operationRef) {
		// Operation의 Reference에서 Submodel ID를 추출하여,
		// 해당 Submodel에 대한 TimeSeriesSubmodelConfig을 조회한다.
		SubmodelElementIdentifier opId = SubmodelElementIdentifier.fromReference(operationRef);
		String smId = opId.getSubmodelId();
		
		List<TimeSeriesSubmodelConfig> tsSubConfigs = tsPersistStack.asConfig().getTimeSeriesSubmodelConfigs();
		return Funcs.findFirst(tsSubConfigs, cfg -> cfg.getId().equals(smId));
	}
	
	private static DefaultMetadata readMetadata(TimeSeriesPersistenceStack persistence, Reference opRef)
		throws PersistenceException {
		SubmodelElementIdentifier opId = SubmodelElementIdentifier.fromReference(opRef);
		SubmodelElementIdentifier metadataId
				= SubmodelElementIdentifier.builder()
											.submodelId(opId.getSubmodelId())
											.idShortPath(IdShortPath.parse("Metadata"))
											.build();

		try {
			SubmodelElement sme = persistence.getSubmodelElement(metadataId, QueryModifier.DEFAULT);
			DefaultMetadata metadata = new DefaultMetadata();
			metadata.updateFromAasModel(sme);
			
			return metadata;
		}
		catch ( ResourceNotFoundException e ) {
			throw new mdt.model.ResourceNotFoundException("Metadata", "path=Metadata");
		}
	}


	private static final String SQL_READ_RECORDS_IN_RANGE = "%s WHERE timestamp between ? and ? ORDER BY timestamp ASC;";
	private static final String SQL_READ_RECORDS_LATER_THAN = "%s WHERE timestamp >= ? ORDER BY timestamp ASC;";
	private static final String SQL_READ_RECORDS_EARLIER_THAN = "s WHERE timestamp <= ? ORDER BY timestamp ASC;";
	private PreparedStatement prepareStatement(Connection conn, String fullRangeSql, Range timespan) throws SQLException {
		Timestamp minTs = toSqlTimestamp(timespan.getMin());
		Timestamp maxTs = toSqlTimestamp(timespan.getMax());
		
		if ( minTs != null && maxTs != null ) {
			String sql = String.format(SQL_READ_RECORDS_IN_RANGE, fullRangeSql);
			PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setTimestamp(1, minTs);
			pstmt.setTimestamp(2, maxTs);
			return pstmt;
		}
		else if ( minTs != null && maxTs == null ) {
			String sql = String.format(SQL_READ_RECORDS_LATER_THAN, fullRangeSql);
			PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setTimestamp(1, minTs);
			return pstmt;
		}
		else if ( minTs == null && maxTs != null ) {
			String sql = String.format(SQL_READ_RECORDS_EARLIER_THAN, fullRangeSql);
			PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setTimestamp(1, maxTs);
			return pstmt;
		}
		else {
			throw new ModelGenerationException("invalid timespan: " + timespan);
		}
	}
	
	private DefaultRecords readRecords(ResultSet rset)
									throws SQLException, mdt.model.ResourceNotFoundException, PersistenceException {
		DefaultMetadata metadata = getMetadata();
		RecordMetadata recordMetadata = metadata.getRecord();

		List<DefaultRecord> recList
					= JdbcUtils.fstream(rset, recordMetadata::read)
								.zipWithIndex()
								.map(idxed -> new DefaultRecord("" + idxed.index(), recordMetadata, idxed.value()))
								.toList();
		DefaultRecords records = new DefaultRecords();
		records.setRecordList(recList);
		
		return records;
	}
	
	private DefaultLinkedSegment getTargetSegment() throws mdt.model.ResourceNotFoundException, PersistenceException {
		SubmodelElementIdentifier opId = SubmodelElementIdentifier.fromReference(m_opRef);
		SubmodelElementIdentifier fullRangeId
				= SubmodelElementIdentifier.builder()
											.submodelId(opId.getSubmodelId())
											.idShortPath(IdShortPath.parse("Segments.FullRange"))
											.build();
		try {
			SubmodelElement segSme = m_persist.getSubmodelElement(fullRangeId, QueryModifier.DEFAULT);
			DefaultLinkedSegment segment = new DefaultLinkedSegment();
			segment.updateFromAasModel(segSme);
			
			return segment;
		}
		catch ( ResourceNotFoundException e ) {
			throw new mdt.model.ResourceNotFoundException("Segment", "path=Segments.FullRange");
		}
	}
	
	private DefaultMetadata getMetadata() throws mdt.model.ResourceNotFoundException, PersistenceException {
		SubmodelElementIdentifier opId = SubmodelElementIdentifier.fromReference(m_opRef);
		SubmodelElementIdentifier fullRangeId
				= SubmodelElementIdentifier.builder()
											.submodelId(opId.getSubmodelId())
											.idShortPath(IdShortPath.parse("Metadata"))
											.build();
		try {
			SubmodelElement mdSMe = m_persist.getSubmodelElement(fullRangeId, QueryModifier.DEFAULT);
			DefaultMetadata metadata = new DefaultMetadata();
			metadata.updateFromAasModel(mdSMe);
			
			return metadata;
		}
		catch ( ResourceNotFoundException e ) {
			throw new mdt.model.ResourceNotFoundException("Segment", "path=Segments.FullRange");
		}
	}
	
	private static Timestamp toSqlTimestamp(String aasTimestamp) {
		if ( aasTimestamp == null ) {
			return null;
		}
		else {
			Instant instant = DataTypes.DATE_TIME.parseValueString(aasTimestamp);
			return DataTypes.DATE_TIME.toJdbcObject(instant);
		}
	}
}
