package mdt.persistence.timeseries;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
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
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;

import utils.Preconditions;
import utils.stream.FStream;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.assetconnection.operation.OperationProvider;
import mdt.config.MDTService;
import mdt.model.expr.MDTExpressionParser;
import mdt.model.sm.ref.timeseries.TimeSeriesRange;
import mdt.model.timeseries.DefaultMetadata;
import mdt.model.timeseries.Metadata;


/**
 * 시계열 Submodel의 레코드를 {@link TimeSeriesRange} 표현식으로 조회하는 MDT 확장 연산
 * ({@code ReadRecordsByRange}, semanticId
 * {@code https://etri.re.kr/mdt/Submodel/TimeSeries/ReadRecordsByRange/1/1}).
 * <p>
 * 입력 인자는 모두 생략 가능하다.
 * <ul>
 *   <li>{@code Range} &mdash; MdtExpr 표기의 조회 범위(예: {@code last=10}, {@code last=1h@now},
 *       {@code from~to}, {@code from+N}). 생략하면 전체 레코드를 읽는다.
 *   <li>{@code Columns} &mdash; 쉼표로 구분된 읽어올 컬럼명 목록. 생략하면 전체 컬럼을 읽는다.
 * </ul>
 * 조회된 레코드들은 첫번째 출력 인자({@code Records})에 설정된다.
 * <p>
 * IDTA 표준 {@code ReadRecords} 연산({@link ReadRecordsOperation})과 마찬가지로 실제 레코드 조회는
 * {@link JdbcRecordsReader}에 위임하며, 이 클래스는 AAS 연산 인자를 {@link TimeSeriesRange}로
 * 변환하는 어댑터 역할만 담당한다.
 * <p>
 * 연산 제공자는 operation 당 하나의 인스턴스가 생성되어 재사용되므로 여러 요청이 동시에
 * {@link #invokeSync}를 호출할 수 있으나, 본 클래스는 호출 사이에 변경되는 상태를 갖지 않는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ReadRecordsByRangeOperation implements OperationProvider {
	private final Reference m_opRef;
	private final JavaOperationProviderConfig m_config;
	private final Persistence<?> m_persist;
	private final TimeSeriesSubmodelConfig m_tsConfig;
	private final JdbcRecordsReader m_reader;
	
	public ReadRecordsByRangeOperation(ServiceContext serviceContext, Reference operationRef,
										JavaOperationProviderConfig config)
																throws ConfigurationInitializationException {
		m_opRef = operationRef;
		m_config = config;
		
		// Metadata는 최상위 persistence로 읽어 ConcurrentPersistence 등 바깥 스택의 처리를 거치게 하고,
		// 시계열 persistence는 스택 안에서 찾아 시계열 Submodel 설정을 얻는 데만 쓴다.
		// (설정이 없으면 ConfigurationInitializationException이 발생한다)
		m_persist = ((MDTService)serviceContext).getPersistence();
		m_tsConfig = TimeSeriesPersistenceStack.getTimeSeriesSubmodelConfig(m_persist, operationRef);

		try {
			Metadata metadata = readMetadata(m_persist, m_opRef);
			m_reader = new JdbcRecordsReader(metadata, m_tsConfig);
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
									"Input argument 'Range' is not a string Property");
		String rangeExpr = (arg1 != null) ? ((Property)arg1).getValue() : null;
		TimeSeriesRange range = (rangeExpr != null)
								? MDTExpressionParser.parseTimeSeriesRange(rangeExpr).evaluate() : null;

		SubmodelElement arg2 = inputVarList.get("Columns");
		Preconditions.checkArgument(arg2 == null
				|| (arg2 instanceof Property && ((Property)arg2).getValueType() == DataTypeDefXsd.STRING),
									"Input argument 'Columns' is not a string Property");
		String columnsCsv = (arg2 != null) ? ((Property)arg2).getValue() : null;
		List<String> columns = (columnsCsv != null)
								? FStream.of(columnsCsv.split(","))
											.map(String::trim)
											.filter(s -> !s.isEmpty())
											.toList()
								: null;

		SubmodelElementCollection records = m_reader.read(range, columns);
		outputVars[0].setValue(records);
	}
	
	private static DefaultMetadata readMetadata(Persistence<?> persistence, Reference opRef)
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
}
