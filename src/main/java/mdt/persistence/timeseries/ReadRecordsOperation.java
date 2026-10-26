package mdt.persistence.timeseries;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
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
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;

import utils.Preconditions;
import utils.stream.FStream;

import mdt.aas.DataTypes;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.assetconnection.operation.OperationProvider;
import mdt.config.MDTService;
import mdt.model.sm.ref.timeseries.TimeSeriesRange;
import mdt.model.timeseries.DefaultMetadata;
import mdt.model.timeseries.Metadata;


/**
 * 시계열 Submodel의 레코드를 주어진 시간 구간으로 조회하는 IDTA 표준 {@code ReadRecords} 연산.
 * <p>
 * 입력 인자 {@code Timespan}({@code xs:dateTime} 타입의 {@link Range})으로 조회 구간을 지정하면
 * 해당 구간에 속한 레코드들이 출력 인자 {@code Records}에 설정된다. {@code Timespan}의
 * {@code min}과 {@code max} 중 한쪽만 지정된 경우는 각각 '해당 시각 이후'와 '해당 시각 이전'으로
 * 해석하고, 양쪽 모두 지정되지 않은 경우는 오류로 처리한다.
 * <p>
 * {@code Timespan}은 {@link TimeSeriesRange.Absolute}와 의미가 동일하므로, 실제 레코드 조회는
 * MDT 확장 연산인 {@code ReadRecordsByRange}와 동일하게 {@link JdbcRecordsReader}에 위임한다.
 * 즉 이 클래스는 AAS 연산 인자를 {@link TimeSeriesRange}로 변환하는 어댑터 역할만 담당한다.
 * <p>
 * 대상 테이블과 접속 정보는 {@link TimeSeriesSubmodelConfig}에서 얻으며, 레코드 스키마
 * ({@code Metadata})와 JDBC 접속기는 생성자에서 한번만 준비된다.
 * <p>
 * 연산 제공자는 operation 당 하나의 인스턴스가 생성되어 재사용되므로 여러 요청이 동시에
 * {@link #invokeSync}를 호출할 수 있으나, 본 클래스는 호출 사이에 변경되는 상태를 갖지 않는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ReadRecordsOperation implements OperationProvider {
	private final Reference m_opRef;
	private final JavaOperationProviderConfig m_config;
	private final Persistence<?> m_persist;
	private final TimeSeriesSubmodelConfig m_tsConfig;
	private final JdbcRecordsReader m_reader;

	/**
	 * 주어진 operation reference에 해당하는 레코드 조회 연산 제공자를 생성한다.
	 * <p>
	 * 대상 시계열 Submodel의 설정과 레코드 스키마를 미리 읽어두고 레코드 조회기를 준비한다.
	 *
	 * @param serviceContext	연산이 수행될 서비스 컨텍스트. {@link MDTService}이어야 한다.
	 * @param operationRef		대상 operation의 reference.
	 * @param config			Java 연산 제공자 설정.
	 * @throws ConfigurationInitializationException	persistence 스택에 시계열 persistence가 없거나,
	 * 												operation이 속한 Submodel의 시계열 설정을 찾을 수 없거나,
	 * 												{@code Metadata} 조회 또는 조회기 준비에 실패한 경우.
	 */
	public ReadRecordsOperation(ServiceContext serviceContext, Reference operationRef,
								JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		m_opRef = operationRef;
		m_config = config;

		// Metadata는 최상위 persistence로 읽어 ConcurrentPersistence 등 바깥 스택의 처리를 거치게 하고,
		// 시계열 persistence는 스택 안에서 찾아 시계열 Submodel 설정을 얻는 데만 쓴다.
		m_persist = ((MDTService)serviceContext).getPersistence();
		m_tsConfig = TimeSeriesPersistenceStack.getTimeSeriesSubmodelConfig(m_persist, operationRef);

		try {
			Metadata metadata = getMetadata();
			m_reader = new JdbcRecordsReader(metadata, m_tsConfig);
		}
		catch ( PersistenceException e ) {
			throw new ConfigurationInitializationException("failed to read Metadata for TimeSeries submodel: id="
															+ m_tsConfig.getId(), e);
		}
		catch ( IOException e ) {
			throw new ConfigurationInitializationException("failed to initialize JdbcRecordsReader: id="
															+ m_tsConfig.getId(), e);
		}
	}

	/**
	 * {@code Timespan} 입력 인자가 지정한 구간의 레코드를 조회하여 첫번째 출력 인자에 설정한다.
	 *
	 * @param inputVars		입력 인자 목록. {@code xs:dateTime} 타입의 {@link Range}인
	 * 						{@code Timespan}을 포함하여야 한다.
	 * @param inoutputVars	입출력 인자 목록. 사용하지 않는다.
	 * @param outputVars	출력 인자 목록. 첫번째 인자({@code Records})에 조회된 레코드 집합이 설정된다.
	 * @throws IllegalArgumentException	출력 인자가 정의되지 않았거나, {@code Timespan} 인자가 없거나,
	 * 									{@code Timespan}이 {@code xs:dateTime} 타입의 {@link Range}가 아니거나,
	 * 									{@code min}과 {@code max}가 모두 지정되지 않은 경우.
	 * @throws IOException	레코드 조회에 실패한 경우.
	 */
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		Preconditions.checkArgument(outputVars.length > 0, "Output argument 'Records' is missing");

		Map<String, SubmodelElement> inputVarList = FStream.of(inputVars)
															.map(OperationVariable::getValue)
															.tagKey(SubmodelElement::getIdShort)
															.toMap();

		SubmodelElement arg1 = inputVarList.get("Timespan");
		Preconditions.checkNotNullArgument(arg1, "Input argument 'Timespan' is missing");
		Preconditions.checkArgument(arg1 instanceof Range, "Input argument 'Timespan' is not a Range");
		Range timespan = (Range)arg1;
		Preconditions.checkArgument(timespan.getValueType() == DataTypeDefXsd.DATE_TIME,
									"Input argument 'Timespan' is not a dateTime Range: valueType="
										+ timespan.getValueType());

		SubmodelElementCollection records = m_reader.read(toTimeSeriesRange(timespan), null);
		outputVars[0].setValue(records);
	}

	/**
	 * AAS {@code Timespan} 인자를 이에 대응하는 {@link TimeSeriesRange.Absolute}로 변환한다.
	 * <p>
	 * {@code min}/{@code max} 중 지정되지 않은 쪽은 개방 구간으로 처리된다.
	 *
	 * @param timespan	변환할 조회 구간.
	 * @return	변환된 {@link TimeSeriesRange}.
	 * @throws IllegalArgumentException	{@code min}과 {@code max}가 모두 지정되지 않은 경우.
	 */
	private static TimeSeriesRange toTimeSeriesRange(Range timespan) {
		Instant min = toInstant(timespan.getMin());
		Instant max = toInstant(timespan.getMax());
		Preconditions.checkArgument(min != null || max != null,
									"Input argument 'Timespan' has neither 'min' nor 'max': min=%s, max=%s",
									timespan.getMin(), timespan.getMax());

		return TimeSeriesRange.between(min, max);
	}

	/**
	 * 대상 시계열 Submodel의 {@code Metadata}를 읽어 반환한다.
	 *
	 * @return	{@code Metadata} 정보.
	 * @throws mdt.model.ResourceNotFoundException	{@code Metadata}를 찾을 수 없는 경우.
	 * @throws PersistenceException	persistence 접근에 실패한 경우.
	 */
	private DefaultMetadata getMetadata() throws mdt.model.ResourceNotFoundException, PersistenceException {
		SubmodelElementIdentifier opId = SubmodelElementIdentifier.fromReference(m_opRef);
		SubmodelElementIdentifier metadataId
				= SubmodelElementIdentifier.builder()
											.submodelId(opId.getSubmodelId())
											.idShortPath(IdShortPath.parse("Metadata"))
											.build();
		try {
			SubmodelElement mdSMe = m_persist.getSubmodelElement(metadataId, QueryModifier.DEFAULT);
			DefaultMetadata metadata = new DefaultMetadata();
			metadata.updateFromAasModel(mdSMe);

			return metadata;
		}
		catch ( ResourceNotFoundException e ) {
			throw new mdt.model.ResourceNotFoundException("Metadata", "path=Metadata");
		}
	}

	/**
	 * AAS의 {@code xs:dateTime} 문자열을 {@link Instant}로 변환한다.
	 *
	 * @param aasTimestamp	변환할 시각 문자열. {@code null}이거나 빈 문자열인 경우는
	 * 						{@code null}이 반환된다.
	 * @return	변환된 {@link Instant} 객체.
	 */
	private static Instant toInstant(String aasTimestamp) {
		return (aasTimestamp != null) ? DataTypes.DATE_TIME.parseValueString(aasTimestamp) : null;
	}
}
