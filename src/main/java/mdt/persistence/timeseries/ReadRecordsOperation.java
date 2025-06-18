package mdt.persistence.timeseries;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Range;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import com.google.common.base.Preconditions;

import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;
import utils.jdbc.JdbcUtils;
import utils.stream.FStream;

import mdt.aas.DataTypes;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.assetconnection.operation.OperationProvider;
import mdt.model.ModelGenerationException;
import mdt.model.timeseries.DefaultLinkedSegment;
import mdt.model.timeseries.DefaultMetadata;
import mdt.model.timeseries.DefaultRecord;
import mdt.model.timeseries.DefaultRecords;
import mdt.model.timeseries.RecordMetadata;

import de.fraunhofer.iosb.ilt.faaast.service.Service;
import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ReadRecordsOperation implements OperationProvider {
	private final Reference m_opRef;
	private final JavaOperationProviderConfig m_config;
	private final Persistence m_persist;
	
	public ReadRecordsOperation(ServiceContext serviceContext, Reference operationRef,
								JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		m_opRef = operationRef;
		m_config = config;
		
		m_persist = ((Service)serviceContext).getPersistence();
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		Map<String, SubmodelElement> inputVarList = FStream.of(inputVars)
															.map(OperationVariable::getValue)
															.tagKey(SubmodelElement::getIdShort)
															.toMap();
		
		SubmodelElement arg1 = inputVarList.get("Timespan");
		Preconditions.checkArgument(arg1 != null, "Input argument 'Timespan' is missing");
		Preconditions.checkArgument(arg1 instanceof Range, "Input argument 'Timespan' is not a Range");
		Range timespan = (Range)arg1;
		
		DefaultLinkedSegment wholeSegment = getTargetSegment();
		JdbcConfiguration jdbcConf = JdbcConfiguration.parseString(wholeSegment.getEndpoint());
		JdbcProcessor jdbc = JdbcProcessor.create(jdbcConf);
		try ( Connection conn = jdbc.connect();
			PreparedStatement pstmt = prepareStatement(conn, wholeSegment.getQuery(), timespan);
			ResultSet rset = pstmt.executeQuery() ) {
			DefaultRecords records = readRecords(rset);
			
			outputVars[0].setValue(records.newSubmodelElement());
		}
	}


	private static final String SQL_READ_RECORDS_IN_RANGE = "%s WHERE timestamp between ? and ? ORDER BY timestamp ASC;";
	private static final String SQL_READ_RECORDS_LATER_THAN = "%s WHERE timestamp >= ? ORDER BY timestamp ASC;";
	private static final String SQL_READ_RECORDS_EARLIER_THAN = "s WHERE timestamp <= ? ORDER BY timestamp ASC;";
	private PreparedStatement prepareStatement(Connection conn, String wholeSql, Range timespan) throws SQLException {
		Timestamp minTs = toSqlTimestamp(timespan.getMin());
		Timestamp maxTs = toSqlTimestamp(timespan.getMax());
		
		if ( minTs != null && maxTs != null ) {
			String sql = String.format(SQL_READ_RECORDS_IN_RANGE, wholeSql);
			PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setTimestamp(1, minTs);
			pstmt.setTimestamp(2, maxTs);
			return pstmt;
		}
		else if ( minTs != null && maxTs == null ) {
			String sql = String.format(SQL_READ_RECORDS_LATER_THAN, wholeSql);
			PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setTimestamp(1, minTs);
			return pstmt;
		}
		else if ( minTs == null && maxTs != null ) {
			String sql = String.format(SQL_READ_RECORDS_EARLIER_THAN, wholeSql);
			PreparedStatement pstmt = conn.prepareStatement(sql);
			pstmt.setTimestamp(1, maxTs);
			return pstmt;
		}
		else {
			throw new ModelGenerationException("invalid timespan: " + timespan);
		}
	}
	
	private DefaultRecords readRecords(ResultSet rset) throws SQLException {
		DefaultMetadata metadata = getMetadata();
		RecordMetadata recordMetadata = metadata.getRecordMetadata();

		List<DefaultRecord> recList
					= JdbcUtils.fstream(rset, recordMetadata::read)
								.zipWithIndex()
								.map(idxed -> new DefaultRecord("" + idxed.index(), recordMetadata, idxed.value()))
								.toList();
		DefaultRecords records = new DefaultRecords();
		records.setRecordList(recList);
		
		return records;
	}
	
	private DefaultLinkedSegment getTargetSegment() throws mdt.model.ResourceNotFoundException {
		SubmodelElementIdentifier opId = SubmodelElementIdentifier.fromReference(m_opRef);
		SubmodelElementIdentifier wholeId
				= SubmodelElementIdentifier.builder()
											.submodelId(opId.getSubmodelId())
											.idShortPath(IdShortPath.parse("Segments.Whole"))
											.build();
		try {
			SubmodelElement segSme = m_persist.getSubmodelElement(wholeId, QueryModifier.DEFAULT);
			DefaultLinkedSegment segment = new DefaultLinkedSegment();
			segment.updateFromAasModel(segSme);
			
			return segment;
		}
		catch ( ResourceNotFoundException e ) {
			throw new mdt.model.ResourceNotFoundException("Segment", "path=Segments.Whole");
		}
	}
	
	private DefaultMetadata getMetadata() throws mdt.model.ResourceNotFoundException {
		SubmodelElementIdentifier opId = SubmodelElementIdentifier.fromReference(m_opRef);
		SubmodelElementIdentifier wholeId
				= SubmodelElementIdentifier.builder()
											.submodelId(opId.getSubmodelId())
											.idShortPath(IdShortPath.parse("Metadata"))
											.build();
		try {
			SubmodelElement mdSMe = m_persist.getSubmodelElement(wholeId, QueryModifier.DEFAULT);
			DefaultMetadata metadata = new DefaultMetadata();
			metadata.updateFromAasModel(mdSMe);
			
			return metadata;
		}
		catch ( ResourceNotFoundException e ) {
			throw new mdt.model.ResourceNotFoundException("Segment", "path=Segments.Whole");
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
