package mdt.persistence.asset.http;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

import utils.Preconditions;
import utils.UnitUtils;
import utils.json.JacksonDeserializationException;
import utils.json.JacksonUtils;

import mdt.persistence.asset.AbstractAssetVariableConfig;
import mdt.persistence.asset.AssetVariableConfig;


/**
 * {@link HttpAssetVariable}의 설정.
 * <p>
 * 지정된 URL에 HTTP {@code GET} 요청을 보내 응답 본문을 읽고, 그 값을 대상 SubmodelElement에 반영하는
 * 방법을 기술한다. 응답 본문은 JSON으로 해석하며, JSON이 아니면 본문 전체를 하나의 문자열 값으로 취급한다.
 * <p>
 * 응답의 어느 부분을 사용할지는 두 단계의 <a href="https://datatracker.ietf.org/doc/html/rfc6901">JSON
 * Pointer</a>로 지정한다.
 * <ol>
 *   <li>{@code valuePointer} — 응답 본문에서 값의 기준 위치를 선택한다. 생략하면 본문 전체가 기준이 된다.</li>
 *   <li>{@code mappings} — 기준 위치로부터의 상대 JSON Pointer를 대상 SubmodelElement 내부의 하위 element
 *       (상대 idShort 경로)에 대응시킨다. 생략하면 기준 위치의 값 전체를 대상 SubmodelElement에 반영한다.</li>
 * </ol>
 * <p>
 * 설정 예:
 * <pre>{@code
 * {
 *     "@type": "mdt:asset:http",
 *     "element": "param:data:tcncdata",
 *     "validPeriod": "1s",
 *     "aasHubName": "default",
 *     "baseUrl": "http://34.50.8.56:8080/api/v1",
 *     "headers": { "Authorization": "Bearer ${env:SENSOR_TOKEN}" },
 *     "timeout": "3s",
 *     "valuePointer": "/data",
 *     "mappings": {
 *         "Temperature": "/temperature",
 *         "Humidity": "/humidity",
 *         "Readings[0]": "/readings/0"
 *     }
 * }
 * }</pre>
 * <p>
 * <b>JSON Pointer에서 배열 원소는 {@code /name/0} 형식으로 지정한다.</b> {@code /name[0]}은 문법적으로는
 * 유효하지만 {@code "name[0]"}이라는 이름의 필드를 찾으므로 항상 값이 없다. 반면 mapping의 키는 AAS idShort
 * 경로이므로 {@code Readings[0]}처럼 대괄호 형식을 쓴다 — 두 쪽의 문법이 다르다는 점에 주의한다.
 * <p>
 * {@code ${env:...}} 형식의 변수는 인스턴스 설정 파일을 읽을 때 일괄 치환되므로, {@code url}과
 * {@code headers}에서도 그대로 사용할 수 있다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class HttpAssetVariableConfig extends AbstractAssetVariableConfig implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:http";

	private static final String FIELD_URL = "url";
	private static final String FIELD_HEADERS = "headers";
	private static final String FIELD_TIMEOUT = "timeout";
	private static final String FIELD_VALUE_POINTER = "valuePointer";
	private static final String FIELD_MAPPINGS = "mappings";

	private String m_url;
	private Map<String,String> m_headers = Map.of();
	private @Nullable Duration m_timeout;
	private String m_valuePointer = "";
	private List<SubPathToJsonPointer> m_mappings = List.of();

	/**
	 * 대상 SubmodelElement 내부의 idShort 경로와 응답 값 내부의 JSON Pointer 사이의 대응.
	 *
	 * @param subPath	대상 SubmodelElement 기준의 상대 idShort 경로. 빈 문자열이면 대상 SubmodelElement 자신.
	 * @param pointer	{@code valuePointer}로 선택된 기준 위치로부터의 상대 JSON Pointer.
	 * 					빈 문자열이면 기준 위치의 값 전체.
	 */
	public static record SubPathToJsonPointer(String subPath, String pointer) { }

	private HttpAssetVariableConfig() { }

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}

	public String getUrl() {
		return m_url;
	}

	/**
	 * 요청에 추가할 HTTP 헤더를 반환한다.
	 *
	 * @return	헤더 이름 → 값. 설정되지 않았으면 빈 맵. (수정 불가)
	 */
	public Map<String,String> getHeaders() {
		return m_headers;
	}

	/**
	 * HTTP 호출 전체(연결·요청·응답 수신)에 허용되는 최대 시간을 반환한다.
	 *
	 * @return	호출 제한 시간. 설정되지 않았으면 {@code null}이며, 이 경우 HTTP 클라이언트의 기본값을 따른다.
	 */
	public @Nullable Duration getTimeout() {
		return m_timeout;
	}

	/**
	 * 응답 본문에서 값의 기준 위치를 가리키는 JSON Pointer를 반환한다.
	 *
	 * @return	JSON Pointer. 빈 문자열이면 본문 전체가 기준이 된다.
	 */
	public String getValuePointer() {
		return m_valuePointer;
	}

	/**
	 * 기준 위치의 값과 SubmodelElement 하위 element 사이의 대응을 반환한다.
	 *
	 * @return	mapping 리스트. 설정되지 않았으면 빈 리스트이며, 이 경우 기준 위치의 값 전체가 대상
	 * 			SubmodelElement에 반영된다. (수정 불가)
	 */
	public List<SubPathToJsonPointer> getMappings() {
		return m_mappings;
	}

	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);

		gen.writeStringField(FIELD_URL, m_url);
		if ( !m_headers.isEmpty() ) {
			gen.writeObjectFieldStart(FIELD_HEADERS);
			for ( Map.Entry<String,String> ent: m_headers.entrySet() ) {
				gen.writeStringField(ent.getKey(), ent.getValue());
			}
			gen.writeEndObject();
		}
		if ( m_timeout != null ) {
			gen.writeStringField(FIELD_TIMEOUT, m_timeout.toString());
		}
		if ( !m_valuePointer.isEmpty() ) {
			gen.writeStringField(FIELD_VALUE_POINTER, m_valuePointer);
		}
		if ( !m_mappings.isEmpty() ) {
			// 읽을 때와 같은 객체 형식(subPath → pointer)으로 기록하여, 직렬화 결과를 다시 읽어도
			// 동일한 설정이 복원되도록 한다.
			gen.writeObjectFieldStart(FIELD_MAPPINGS);
			for ( SubPathToJsonPointer mapping: m_mappings ) {
				gen.writeStringField(mapping.subPath(), mapping.pointer());
			}
			gen.writeEndObject();
		}
	}

	/**
	 * JSON 노드로부터 {@link HttpAssetVariableConfig} 객체를 생성한다.
	 * <p>
	 * 모든 JSON Pointer는 이 시점에 문법을 검사한다. 문법이 잘못된 pointer(예: {@code '/'}로 시작하지 않는
	 * {@code "data/value"})는 인스턴스 기동 시점에 바로 드러나도록 예외로 처리한다.
	 *
	 * @param jnode	JSON 노드.
	 * @return	생성된 {@link HttpAssetVariableConfig} 객체.
	 * @throws IOException	필수 필드({@code element}, {@code url})가 없거나, 필드 값의 형식이 잘못된 경우.
	 */
	public static HttpAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		HttpAssetVariableConfig config = new HttpAssetVariableConfig();
		config.loadFields(jnode);

		config.m_url = JacksonUtils.getStringField(jnode, FIELD_URL);
		if ( config.m_url.isBlank() ) {
			throw new JacksonDeserializationException("'" + FIELD_URL + "' is blank: json=" + jnode);
		}

		JsonNode headersNode = JacksonUtils.getFieldOrNull(jnode, FIELD_HEADERS);
		if ( headersNode != null ) {
			if ( !headersNode.isObject() ) {
				throw new JacksonDeserializationException("'" + FIELD_HEADERS + "' must be an object: json=" + jnode);
			}
			Map<String,String> headers = new LinkedHashMap<>();
			for ( Map.Entry<String,JsonNode> ent: headersNode.properties() ) {
				headers.put(ent.getKey(), ent.getValue().asText());
			}
			config.m_headers = Collections.unmodifiableMap(headers);
		}

		String timeoutStr = JacksonUtils.getStringFieldOrNull(jnode, FIELD_TIMEOUT);
		config.m_timeout = (timeoutStr != null) ? UnitUtils.parseDuration(timeoutStr) : null;

		String valuePointer = JacksonUtils.getStringFieldOrNull(jnode, FIELD_VALUE_POINTER);
		config.m_valuePointer = (valuePointer != null) ? checkJsonPointer(valuePointer, FIELD_VALUE_POINTER) : "";

		JsonNode mappingsNode = JacksonUtils.getFieldOrNull(jnode, FIELD_MAPPINGS);
		if ( mappingsNode != null ) {
			if ( !mappingsNode.isObject() ) {
				throw new JacksonDeserializationException("'" + FIELD_MAPPINGS
														+ "' must be an object (subPath → JSON Pointer): json=" + jnode);
			}
			List<SubPathToJsonPointer> mappings = new ArrayList<>();
			for ( Map.Entry<String,JsonNode> ent: mappingsNode.properties() ) {
				String pointer = checkJsonPointer(ent.getValue().asText(), FIELD_MAPPINGS + "." + ent.getKey());
				mappings.add(new SubPathToJsonPointer(ent.getKey(), pointer));
			}
			config.m_mappings = Collections.unmodifiableList(mappings);
		}

		return config;
	}

	private static String checkJsonPointer(String pointer, String fieldName) throws IOException {
		Preconditions.checkNotNullArgument(pointer, "pointer is null");
		try {
			JsonPointer.compile(pointer);
			return pointer;
		}
		catch ( IllegalArgumentException e ) {
			throw new JacksonDeserializationException(String.format("invalid JSON Pointer in '%s': '%s' "
																	+ "(must be empty or start with '/')",
																	fieldName, pointer));
		}
	}

	@Override
	public String toString() {
		return String.format("%s, url=%s", super.toString(), m_url);
	}
}
