package mdt.persistence.asset.http;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import okhttp3.OkHttpClient;

import utils.Throwables;
import utils.http.HttpRESTfulClient;
import utils.http.OkHttpClientUtils;
import utils.http.RESTfulException;
import utils.stream.FStream;
import utils.thread.Guard;

import mdt.aas.DataType;
import mdt.aas.DataTypes;
import mdt.model.MDTModelSerDe;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.asset.AbstractAssetVariable;
import mdt.persistence.asset.AssetVariableException;


/**
 * HTTP로 접근 가능한 외부 datasource를 사용하는 읽기 전용 {@code AssetVariable}.
 * <p>
 * 설정된 URL에 HTTP {@code GET} 요청을 보내 응답 본문을 읽고, {@link HttpAssetVariableConfig}에 기술된
 * JSON Pointer에 따라 값을 추출하여 바인딩된 SubmodelElement(prototype)에 반영한다.
 * <ul>
 *   <li>응답 본문은 JSON으로 해석한다. JSON이 아니면 본문 전체를 하나의 문자열 값으로 취급하므로,
 *       {@code "23.5"}처럼 값 하나만 돌려주는 단순한 endpoint도 그대로 사용할 수 있다.</li>
 *   <li>{@code valuePointer}로 기준 위치를 고르고, {@code mappings}가 있으면 기준 위치로부터의 상대
 *       pointer로 하위 element를 하나씩 채운다. {@code mappings}가 없으면 기준 위치의 값 전체를 대상
 *       SubmodelElement에 반영한다.</li>
 * </ul>
 * {@link AbstractAssetVariable}의 유효 기간 기반 캐시를 사용하므로, 캐시가 만료된 경우에만 실제 HTTP 요청을
 * 보낸다. 공유되는 prototype에 대한 동시 접근을 막기 위해 읽기는 {@link Guard}로 직렬화된다.
 * <p>
 * <b>값이 없는 경우의 처리</b>
 * <ul>
 *   <li>{@code valuePointer}가 가리키는 위치가 응답에 없으면 {@link AssetVariableException}을 던진다.
 *       응답 형식이 설정과 맞지 않는다는 뜻이므로 조용히 넘기지 않는다.</li>
 *   <li>개별 mapping의 pointer가 가리키는 위치가 없으면 경고 로그를 남기고 그 하위 element만 건너뛴다.
 *       기존 값은 그대로 유지된다.</li>
 *   <li>값이 JSON {@code null}이면 갱신하지 않고 직전 값을 유지한다.</li>
 * </ul>
 * <p>
 * 이 datasource는 조회만 지원하므로 {@link #isUpdatable()}은 {@code false}이며 {@link #update}는 항상
 * {@link AssetVariableException}을 던진다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class HttpAssetVariable extends AbstractAssetVariable<HttpAssetVariableConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(HttpAssetVariable.class);
	public static final String CONFIG_GROUP_AASHUBS = "AASHub";

	private final HttpRESTfulClient m_restClient;
	private final JsonPointer m_valuePointer;
	private final List<CompiledMapping> m_mappings;
	private final Guard m_guard = Guard.create();

	/** 설정 파일의 mapping을 매 읽기마다 다시 해석하지 않도록 JSON Pointer를 미리 컴파일해 둔 것. */
	private static record CompiledMapping(String subPath, String pointerExpr, JsonPointer pointer) { }

	/**
	 * 주어진 설정으로 {@code HttpAssetVariable}을 생성한다.
	 * <p>
	 * 설정된 헤더와 호출 제한 시간을 적용한 HTTP 클라이언트를 준비한다. 실제 요청은 {@link #read()}에서
	 * 처음 수행된다.
	 *
	 * @param config	HTTP 접속 정보와 값 추출 방법을 담은 설정.
	 */
	public HttpAssetVariable(HttpAssetVariableConfig config) {
		super(config);

		OkHttpClient httpClient = OkHttpClientUtils.newClient();
		if ( config.getTimeout() != null ) {
			httpClient = httpClient.newBuilder()
									.callTimeout(config.getTimeout())
									.build();
		}
		HttpRESTfulClient.Builder builder = HttpRESTfulClient.builder()
															.httpClient(httpClient)
															.jsonMapper(MDTModelSerDe.getJsonMapper());
		for ( Map.Entry<String,String> header: config.getHeaders().entrySet() ) {
			builder = builder.header(header.getKey(), header.getValue());
		}
		m_restClient = builder.build();

		m_valuePointer = JsonPointer.compile(config.getValuePointer());
		m_mappings = FStream.from(config.getMappings())
							.map(m -> new CompiledMapping(m.subPath(), m.pointer(),
															JsonPointer.compile(m.pointer())))
							.toList();

		setLogger(s_logger);
	}

	/**
	 * HTTP datasource는 조회를 지원하므로 항상 {@code true}를 반환한다.
	 *
	 * @return	항상 {@code true}.
	 */
	@Override
	public boolean isReadable() {
		return true;
	}

	/**
	 * 본 구현은 조회 전용이므로 항상 {@code false}를 반환한다.
	 *
	 * @return	항상 {@code false}.
	 */
	@Override
	public boolean isUpdatable() {
		return false;
	}

	/**
	 * 설정된 URL에서 최신 값을 읽어 prototype SubmodelElement에 반영하고 이를 반환한다.
	 * <p>
	 * 캐시가 만료된 경우에만 HTTP 요청을 보내며, 그렇지 않으면 캐시된 prototype을 사용한다.
	 * 요청이나 값 반영에 실패하면 갱신 시각을 바꾸지 않으므로, 다음 호출에서 곧바로 다시 시도한다.
	 * <p>
	 * 반환값은 {@link Guard}로 보호되는 임계 구역 안에서 만든 prototype의 복사본이므로,
	 * 호출자가 변경하거나 보관해도 캐시에 영향을 주지 않는다.
	 *
	 * @return	prototype SubmodelElement의 복사본.
	 * @throws AssetVariableException	HTTP 요청이 실패했거나, 응답에서 값을 추출·변환하지 못한 경우.
	 */
	@Override
	public SubmodelElement read() throws AssetVariableException {
		m_guard.lock();
		try {
			Instant now = Instant.now();
			if ( isExpired(now) ) {
				loadOnBuffer(fetch());
				setLastRefreshTime(now);
			}

			// 캐시 자체를 반환하면 잠금을 푼 뒤 다른 쓰레드의 적재와 겹치므로 복사본을 반환한다.
			return SubmodelUtils.duplicate(m_prototype);
		}
		finally {
			m_guard.unlock();
		}
	}

	/**
	 * 본 구현은 조회 전용이므로 항상 예외를 던진다.
	 *
	 * @param newElement	(사용되지 않음)
	 * @throws AssetVariableException	항상.
	 */
	@Override
	public void update(SubmodelElement newElement) throws AssetVariableException {
		throw new AssetVariableException("update is not supported for HTTP AssetVariable: " + this);
	}

	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), getConfig());
	}

	/**
	 * 설정된 URL에 {@code GET} 요청을 보내 응답 본문을 JSON 노드로 반환한다.
	 * <p>
	 * 본문이 JSON이 아니면 본문 전체를 담은 {@link TextNode}를 반환한다.
	 *
	 * @return	응답 본문.
	 * @throws AssetVariableException	요청이 실패했거나 본문이 비어 있는 경우.
	 */
	private JsonNode fetch() throws AssetVariableException {
		String body;
		try {
			body = m_restClient.get(getConfig().getUrl());
		}
		catch ( RESTfulException e ) {
			// 연결 실패, 타임아웃, 4xx/5xx 응답 모두 이 계층으로 올라온다.
			throw new AssetVariableException("Failed to read HTTP asset variable: url=" + getConfig().getUrl(), e);
		}

		if ( body == null || body.isBlank() ) {
			throw new AssetVariableException("Empty response from HTTP asset variable: url=" + getConfig().getUrl());
		}

		try {
			return MDTModelSerDe.getJsonMapper().readTree(body);
			
		}
		catch ( JsonProcessingException e ) {
			// JSON이 아니면 본문 전체를 하나의 문자열 값으로 취급한다. (예: "23.5", "RUNNING")
			return TextNode.valueOf(body.strip());
		}
	}

	/**
	 * 응답 본문에서 값을 추출하여 prototype({@code m_prototype})에 반영한다.
	 *
	 * @param body	응답 본문.
	 * @throws AssetVariableException	{@code valuePointer}가 가리키는 위치가 없거나, 값 변환이 실패한 경우.
	 */
	private void loadOnBuffer(JsonNode body) throws AssetVariableException {
		JsonNode base = body.at(m_valuePointer);
		if ( base.isMissingNode() ) {
			throw new AssetVariableException(String.format("No value at valuePointer '%s' in HTTP response: url=%s",
															getConfig().getValuePointer(), getConfig().getUrl()));
		}

		if ( m_mappings.isEmpty() ) {
			if ( base.isNull() ) {
				getLogger().debug("null value in HTTP response; keeping previous value: url={}",
									getConfig().getUrl());
				return;
			}
			updateElement(m_prototype, base);
			return;
		}

		for ( CompiledMapping mapping: m_mappings ) {
			JsonNode fieldNode = base.at(mapping.pointer());
			if ( fieldNode.isMissingNode() ) {
				String hint = mapping.pointerExpr().contains("[")
							? " (JSON Pointer에서 배열 원소는 '/name/0' 형식으로 지정한다)" : "";
				getLogger().warn("No such field in HTTP response: url={}, pointer={}{}",
									getConfig().getUrl(), mapping.pointerExpr(), hint);
				continue;
			}
			if ( fieldNode.isNull() ) {
				getLogger().debug("Null field in HTTP response: url={}, pointer={}",
									getConfig().getUrl(), mapping.pointerExpr());
				continue;
			}

			updateElement(traverse(mapping.subPath()), fieldNode);
		}
	}

	/**
	 * 주어진 JSON 값으로 대상 SubmodelElement의 값을 갱신한다.
	 * <p>
	 * 대상이 {@link Property}이면 선언된 {@code valueType}에 맞춰 값을 변환한다. 이때 JSON 값은 반드시 스칼라
	 * 값이어야 한다. 객체나 배열을 숫자 타입으로 변환하면 {@link JsonNode#asInt()} 등이 오류 없이 {@code 0}을
	 * 돌려주어 잘못된 값이 조용히 기록되기 때문이다.
	 *
	 * @param target	갱신할 SubmodelElement.
	 * @param value		갱신에 사용할 JSON 값.
	 * @throws AssetVariableException	값의 형태가 대상과 맞지 않거나 변환이 실패한 경우.
	 */
	private void updateElement(SubmodelElement target, JsonNode value) throws AssetVariableException {
		try {
			if ( target instanceof Property prop ) {
				DataType<?> type = DataTypes.fromAas4jDatatype(prop.getValueType());
				if ( value.isContainerNode() ) {
					if ( type.getId().equals("xs:string") ) {
						prop.setValue(value.toString());
					}
					else {
						throw new AssetVariableException(String.format("Property '%s' expects a scalar value, "
																		+ "but got: %s (url=%s)",
																		prop.getIdShort(), value, getConfig().getUrl()));
					}
				}
				else {
					prop.setValue(type.toValueString(type.fromJsonNode(value)));
				}
			}
			else {
				// 그 외의 경우에는 MDT value 형식의 JSON으로 간주하고 갱신한다.
				ElementValues.update(target, value);
			}
		}
		catch ( AssetVariableException e ) {
			throw e;
		}
		catch ( IOException | RuntimeException e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to update %s with value=%s (url=%s)",
										target.getIdShort(), value, getConfig().getUrl());
			throw new AssetVariableException(msg, cause);
		}
	}

	/**
	 * prototype SubmodelElement 내에서 주어진 상대 경로의 하위 element를 찾는다.
	 *
	 * @param subPath	대상 SubmodelElement 기준의 상대 idShort 경로. 빈 문자열이면 prototype 자신.
	 * @return	검색된 SubmodelElement.
	 * @throws AssetVariableException	해당 경로의 element가 없는 경우.
	 */
	private SubmodelElement traverse(String subPath) throws AssetVariableException {
		if ( subPath == null || subPath.isEmpty() ) {
			return m_prototype;
		}

		try {
			return SubmodelUtils.traverse(m_prototype, subPath);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to find sub-element: element=%s, subPath=%s",
										getElementLocation(), subPath);
			throw new AssetVariableException(msg, cause);
		}
	}
}
