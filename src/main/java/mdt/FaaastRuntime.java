package mdt;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.eclipse.digitaltwin.aas4j.v3.model.Message;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.dataformat.DeserializationException;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.http.serialization.HttpJsonApiDeserializer;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.Response;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.StatusCode;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.request.PatchSubmodelElementValueByPathRequest;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.request.submodel.GetSubmodelElementByPathRequest;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.ElementValue;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.ElementValueParser;
import de.fraunhofer.iosb.ilt.faaast.service.typing.TypeInfo;
import de.fraunhofer.iosb.ilt.faaast.service.util.ReferenceBuilder;

import utils.Preconditions;



/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class FaaastRuntime {
	private final ServiceContext m_service;
	
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
	private static FaaastRuntime s_runtime;
	
	FaaastRuntime(ServiceContext service) {
		m_service = service;
	}
	
	public static FaaastRuntime getOrCreate(ServiceContext service) {
		// 다른 서비스로 호출되면(테스트 등) 그 서비스로 새로 만든다. 운영에서는 서비스가 하나뿐이다.
		if ( s_runtime == null || s_runtime.m_service != service ) {
			s_runtime = new FaaastRuntime(service);
		}

		return s_runtime;
	}
	
	public static ExecutorService getExecutor() {
		return EXECUTOR;
	}
	
	public ServiceContext getServiceContext() {
		return m_service;
	}
	
	public SubmodelElement getSubmodelElementByPath(String submodelId, String path) {
		GetSubmodelElementByPathRequest req = GetSubmodelElementByPathRequest.builder()
																			.submodelId(submodelId)
																			.path(path)
																			.build();
		return m_service.execute(req).getPayload();
	}
	
	/**
	 * 주어진 경로의 요소 값을 value-only 문자열로 갱신한다.
	 * <p>
	 * FA³ST는 값 파싱 실패, 요소 없음, 저장 실패 등을 예외 대신 오류 응답으로 반환하므로, 응답 상태를 확인하여
	 * 실패를 {@link ElementUpdateException}으로 알린다.
	 *
	 * @throws ElementUpdateException	FA³ST가 오류 응답을 반환한 경우.
	 */
	public void setSubmodelElementValueByPath(String submodelId, String path, String valueString,
												ElementValueParser<Object> valueParser) {
		PatchSubmodelElementValueByPathRequest<Object> req = PatchSubmodelElementValueByPathRequest.builder()
																						.path(path)
																						.submodelId(submodelId)
																						.value(valueString)
																						.valueParser(valueParser)
																						.build();
		checkSuccess(m_service.execute(req), "update", submodelId, path);
	}
	
	/**
	 * 주어진 요소의 값을 갱신하는 {@link ElementValueUpdater}를 만든다.
	 * <p>
	 * 요소의 타입 정보를 이때 한 번 조회하므로, 요소가 없으면 여기서 실패한다. 반환된 갱신기는 갱신할 때마다
	 * 타입 정보를 다시 조회하지 않는다. 요소 위치는 미리 활성화되어 있어야 한다.
	 *
	 * @param loc	갱신할 요소의 위치.
	 * @return	값 갱신기.
	 * @throws ElementUpdateException	요소가 없거나 타입 정보를 읽지 못한 경우.
	 */
	public ElementValueUpdater newValueUpdater(ElementLocation loc) {
		Preconditions.checkNotNullArgument(loc, "ElementLocation is null");
		
		String submodelId = loc.getSubmodelId();
		String path = loc.getElementPath();
		TypeInfo<?> typeInfo;
		try {
			typeInfo = m_service.getTypeInfo(new ReferenceBuilder()
													.submodel(submodelId)
													.idShortPath(path)
													.build());
		}
		catch ( ResourceNotFoundException e ) {
			throw new ElementUpdateException("element not found: " + loc.toStringExpr(), e);
		}
		catch ( PersistenceException e ) {
			throw new ElementUpdateException("failed to read the type of element: " + loc.toStringExpr(), e);
		}
		if ( typeInfo == null ) {
			throw new ElementUpdateException("element not found: " + loc.toStringExpr(), (StatusCode)null);
		}
		
		ElementValueParser<Object> parser = newElementValueParser(typeInfo);
		return new ElementValueUpdater() {
			@Override
			public ElementLocation getLocation() {
				return loc;
			}

			@Override
			public Class<?> getValueType() {
				return typeInfo.getType();
			}

			@Override
			public void update(String valueJson) {
				setSubmodelElementValueByPath(submodelId, path, valueJson, parser);
			}
			
			@Override
			public String toString() {
				return "ElementValueUpdater[" + loc.toStringExpr() + "]";
			}
		};
	}
	
	private static void checkSuccess(Response resp, String action, String submodelId, String path) {
		StatusCode status = (resp != null) ? resp.getStatusCode() : null;
		if ( status != null && status.isSuccess() ) {
			return;
		}
		
		List<Message> messages = (resp != null && resp.getResult() != null) ? resp.getResult().getMessages() : null;
		String details = (messages != null)
						? messages.stream().map(Message::getText).collect(Collectors.joining("; "))
						: "";
		throw new ElementUpdateException(String.format("failed to %s element: submodel=%s, path=%s, status=%s, cause=%s",
														action, submodelId, path, status, details), status);
	}
	
	// 미리 조회한 타입 정보로 value-only JSON을 파싱하는 parser.
	private static ElementValueParser<Object> newElementValueParser(TypeInfo<?> typeInfo) {
		return new ElementValueParser<Object>() {
			@Override
			public <U extends ElementValue> U parse(Object raw, Class<U> type) throws DeserializationException {
				if ( !ElementValue.class.isAssignableFrom(type) ) {
					throw new DeserializationException(
							String.format("error deserializing payload - invalid type '%s' (must be ElementValue)",
											type.getSimpleName()));
				}
				return API_DESERIALIZER.readValue(raw.toString(), typeInfo);
			}
		};
	}
	
	private static HttpJsonApiDeserializer API_DESERIALIZER = new HttpJsonApiDeserializer();
}
