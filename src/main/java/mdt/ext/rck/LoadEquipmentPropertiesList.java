package mdt.ext.rck;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.UnitUtils;

import mdt.assetconnection.operation.AbstractJavaOperationProvider;
import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.value.PropertyValue;


/**
 * 시뮬레이터가 제공하는 레이아웃의 설비 속성 목록을 JSON 문자열로 출력하는 MDT 오퍼레이션.
 * <p>
 * 호출될 때마다 {@link RCKSimulation.Builder#loadEquipmentProperties()}로 시뮬레이터에 접속하여 레이아웃을 요청하고,
 * {@code facility} 메시지를 받으면 연결을 닫는다. 시작 명령과 중지 명령은 보내지 않으므로 시뮬레이션은 실행되지 않는다.
 * 다만 같은 공정에서 시뮬레이션이 실행 중일 때 레이아웃 요청이 주는 영향은 확인되지 않았다.
 * <p>
 * 한 오퍼레이션은 한 번에 하나의 요청만 처리한다. 시뮬레이터에 같은 {@code clientId}로 등록하기 때문이다. 처리 중에
 * 들어온 호출은 접속하지 않고 {@link IllegalStateException}을 던진다.
 * <p>
 * 입출력 변수:
 * <ul>
 *   <li>{@code LayoutName} (입력, {@code xs:string}, 필수) - 설비 속성을 읽을 레이아웃 이름. 빈 값이나 공백은 안 된다.</li>
 *   <li>{@code EquipmentPropertiesList} (출력, {@code xs:string}, 필수) - 설비 속성 목록.
 *       {@link RCKEquipmentProperties#toJsonNode()} 형식, 즉 시뮬레이터가 보낸 {@code message}와 같은 형식의 JSON
 *       문자열이다.</li>
 * </ul>
 * 설정 인자:
 * <ul>
 *   <li>{@code clientId} / {@code processName} / {@code rckServerUrl} (필수) - 시뮬레이터 등록 ID, 공정명,
 *       WebSocket 서버 URL.</li>
 *   <li>{@code responseTimeout} (선택, 기본 2분) - 시뮬레이터 응답 대기 시간
 *       ({@link RCKSimulation.Builder#responseTimeout}). 시간 값은 {@link UnitUtils#parseDuration(String)} 형식이다.</li>
 * </ul>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class LoadEquipmentPropertiesList extends AbstractJavaOperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(LoadEquipmentPropertiesList.class);

	private static final String VAR_LAYOUT_NAME = "LayoutName";
	private static final String VAR_EQUIPMENT_PROPERTIES_LIST = "EquipmentPropertiesList";

	private final String m_clientId;
	private final String m_processName;
	private final String m_rckServerUrl;
	private final Duration m_responseTimeout;

	// 처리 중인 요청이 있는지 여부. 한 번에 하나만 처리하며, 처리 중의 새 호출은 거부한다.
	private final AtomicBoolean m_running = new AtomicBoolean();

	public LoadEquipmentPropertiesList(ServiceContext serviceContext, Reference opRef,
										JavaOperationProviderConfig config)
		throws ConfigurationInitializationException {
		super(serviceContext, opRef, config);

		m_clientId = getRequiredNonEmptyArgument("clientId");
		m_processName = getRequiredNonEmptyArgument("processName");
		m_rckServerUrl = getRequiredNonEmptyArgument("rckServerUrl");
		m_responseTimeout = getResponseTimeoutArgument();

		s_logger.info("Operation is ready: clientId={}, processName={}, rckServerUrl={}",
						m_clientId, m_processName, m_rckServerUrl);
	}

	/**
	 * 설비 속성 목록을 읽어 출력 변수 {@code EquipmentPropertiesList}에 기록한다. 호출한 스레드는 끝날 때까지 기다린다.
	 *
	 * @throws IllegalArgumentException	입력이나 출력 변수가 없거나, 타입이 맞지 않거나, {@code LayoutName}이 비어 있는 경우.
	 * 									이 경우 시뮬레이터에 접속하지 않는다.
	 * @throws IllegalStateException	이 오퍼레이션이 다른 요청을 처리 중인 경우.
	 * @throws java.util.concurrent.ExecutionException	설비 속성을 얻지 못한 경우. 원인은 접속 실패, 응답 대기 시간 초과,
	 * 													연결 종료, {@code facility} 메시지 형식 오류 등이다.
	 * @throws InterruptedException	기다리는 중 인터럽트된 경우. 이때 시뮬레이터와의 연결은 취소된다.
	 */
	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
								Map<String, SubmodelElement> inoutputVarList,
								Map<String, SubmodelElement> outputVarList) throws Exception {
		String layoutName = getRequiredStringVariableValue(inputVarList, VAR_LAYOUT_NAME);
		if ( layoutName == null || layoutName.isBlank() ) {
			throw new IllegalArgumentException("input variable '" + VAR_LAYOUT_NAME + "' is empty");
		}
		Property output = getRequiredPropertyVariable(outputVarList, VAR_EQUIPMENT_PROPERTIES_LIST,
														DataTypeDefXsd.STRING);

		if ( !m_running.compareAndSet(false, true) ) {
			throw new IllegalStateException("a request of this operation is already running");
		}
		try {
			RCKEquipmentProperties properties = RCKSimulation.builder()
															.serverUrl(m_rckServerUrl)
															.clientId(m_clientId)
															.processName(m_processName)
															.layoutName(layoutName)
															.responseTimeout(m_responseTimeout)
															.loadEquipmentProperties();
			ElementValues.update(output, PropertyValue.STRING(properties.toJsonNode().toPrettyString()));
		}
		finally {
			m_running.set(false);
		}
	}

	private String getRequiredNonEmptyArgument(String name) throws ConfigurationInitializationException {
		String value = assertStringArgument(name);
		if ( value.isBlank() ) {
			throw new ConfigurationInitializationException("argument '" + name + "' is empty: " + m_config);
		}
		return value;
	}

	private Duration getResponseTimeoutArgument() throws ConfigurationInitializationException {
		String str = getStringArgument("responseTimeout");
		if ( str == null ) {
			return RCKSimulation.DEFAULT_RESPONSE_TIMEOUT;
		}
		try {
			Duration timeout = UnitUtils.parseDuration(str);
			if ( !timeout.isPositive() ) {
				throw new IllegalArgumentException("not positive");
			}
			return timeout;
		}
		catch ( RuntimeException e ) {
			throw new ConfigurationInitializationException("invalid argument 'responseTimeout': " + str
															+ " (" + e.getMessage() + ")");
		}
	}
}
