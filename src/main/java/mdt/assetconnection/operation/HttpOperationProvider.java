package mdt.assetconnection.operation;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;

import utils.func.Nullables;
import utils.http.OkHttpClientUtils;
import utils.rpc.restful.RESTfulAsyncRpcClient;
import utils.rpc.restful.RpcRequestMessage;
import utils.stream.FStream;

import mdt.model.MDTModelSerDe;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;


/**
 * HTTP 연산 서버({@code mdt-operation-http}의 {@code OperationController})에 등록된 연산을
 * {@link RESTfulAsyncRpcClient}로 호출하는 {@link OperationProvider}.
 * <p>
 * 설정({@link HttpOperationProviderConfig})의 {@code baseUrl}은 연산 서버의 기본 URL(예: {@code http://localhost:12987})이고,
 * {@code endpoint}는 연산 경로(예: {@code /api/v1/operations/<연산 디렉터리 이름>})다. 연산은
 * {@code POST {baseUrl}{endpoint}}로 시작되며, 이후 응답의 세션 경로를 {@code pollInterval}(기본 3초)마다 조회하여 완료를
 * 기다린다. {@code timeout}이 지나면 실패로 끝나지만 원격 세션은 취소하지 않는다.
 * <p>
 * 입력 인자와 출력 인자 원형을 SubmodelElement JSON으로 보낸다. 입출력 인자는 지원하지 않는다.
 * <p>
 * 기다리는 중 인터럽트되면(AAS 연산 취소) 연산 서버의 세션에 취소({@code DELETE})를 보내고, 세션이 끝나기를
 * 기다린 뒤 {@link InterruptedException}을 던진다({@link OperationProvider#runCancellingOnInterrupt}).
 *
 * @author Kang-Woo Lee (ETRI)
 */
class HttpOperationProvider implements OperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(HttpOperationProvider.class);
	private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(3);
	
	@SuppressWarnings("unused")
	private final ServiceContext m_svcContext;
	private final HttpOperationProviderConfig m_config;
	
	HttpOperationProvider(ServiceContext context, Reference operationRef, HttpOperationProviderConfig config) {
		m_svcContext = context;
		m_config = config;
		
		if ( s_logger.isInfoEnabled() ) {
			IdShortPath idShortPath = IdShortPath.fromReference(operationRef);
			String url = m_config.getBaseUrl() + m_config.getEndpoint();
			s_logger.info("Operation: Http, url={}, poll={}, op-ref={}",
							url, m_config.getPollInterval(), idShortPath);
		}
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		Preconditions.checkArgument(inoutputVars == null || inoutputVars.length == 0,
									"inoutput parameters are not supported in HttpOperation");
		
		// 입력 인자뿐 아니라 출력 인자도 SubmodelElement JSON으로 함께 전달하여, 서버가 출력 값을
		// 해당 SubmodelElement 타입에 맞는 ElementValue로 반환하도록 한다.
		RpcRequestMessage reqMsg = new RpcRequestMessage(toArgumentMap(inputVars), toArgumentMap(outputVars));
		RESTfulAsyncRpcClient client = RESTfulAsyncRpcClient.builder()
													.setHttpClient(OkHttpClientUtils.newClient())
													.setBaseUrl(m_config.getBaseUrl())
													.setOperationEndpoint(m_config.getEndpoint())
													.setPollInterval(Nullables.orElse(m_config.getPollInterval(),
																					DEFAULT_POLL_INTERVAL))
													.setTimeout(m_config.getTimeout())
													.setRequestMessage(reqMsg)
													.setJsonMapper(MDTModelSerDe.getJsonMapper())
													.build();
		
		// AAS 연산 취소(실행 스레드 인터럽트)가 연산 서버의 세션 취소(DELETE)로 이어지도록 별도 스레드에서 실행한다.
		Map<String,JsonNode> outputs = OperationProvider.runCancellingOnInterrupt(client);
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("HttpOperation terminates: outputs={}", outputs.keySet());
		}
		
		updateOutputVariables(outputs, outputVars);
	}
	
	private Map<String,JsonNode> toArgumentMap(OperationVariable[] vars) {
		return FStream.of(vars)
						.map(OperationVariable::getValue)
						.tagKey(SubmodelElement::getIdShort)
						.mapValue(sme -> MDTModelSerDe.toJsonNode(sme))
						.toMap();
	}
	
	/**
	 * 연산 수행 결과를 출력 인자에 반영한다.
	 * <p>
	 * 서버는 출력 값을 {@code @type}이 포함된 {@link ElementValue} JSON으로 반환하거나, 프로토타입이 없는
	 * 경우에는 일반 JSON 값으로 반환하므로 두 형태를 모두 처리한다. 결과에 포함되지 않은 출력 인자는
	 * 갱신하지 않는다.
	 */
	private void updateOutputVariables(Map<String,JsonNode> outputs, OperationVariable[] outputVars)
		throws IOException {
		for ( OperationVariable outVar: outputVars ) {
			SubmodelElement sme = outVar.getValue();
			JsonNode jnode = outputs.get(sme.getIdShort());
			if ( jnode != null ) {
				ElementValue smev = ElementValues.parseJsonNode(jnode, ElementValues.getValue(sme));
				ElementValues.update(sme, smev);
			}
		}
	}
}
