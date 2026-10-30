package mdt;

import java.io.IOException;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import utils.Preconditions;
import utils.json.JacksonUtils;

import mdt.model.MDTModelSerDe;


/**
 * AssetVariable·연산 설정에서 외부 연결 설정(JDBC, MQTT broker, 로봇 트윈 등)을 가리키는 참조.
 * <p>
 * 연결 설정은 전역 설정 파일의 항목 이름과 설정 객체 중 정확히 하나로 지정한다.
 * JSON에서는 하나의 필드로 기술하며, 값이 문자열이면 전역 설정 이름으로, 객체이면 연결 설정으로 해석한다.
 * <pre>{@code
 * "jdbcConfig": "factory"                                  // 전역 설정 jdbcs.factory
 * "jdbcConfig": { "jdbcUrl": "jdbc:postgresql://..." }     // 인라인 연결 설정
 * }</pre>
 * 필드를 생략하면 전역 설정의 기본 이름({@code default})을 사용한다. 이전 형식과의 호환을 위해
 * 전역 설정 이름 전용 필드(예: {@code jdbcConfigName})도 받지만, 두 필드를 함께 사용할 수는 없다.
 *
 * @param <T>	연결 설정 타입.
 * @param name		전역 설정 항목 이름. {@code inline}이 지정된 경우는 {@code null}.
 * @param inline	설정에 직접 기술된 연결 설정. {@code name}이 지정된 경우는 {@code null}.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public record ConnectionConfigRef<T>(@Nullable String name, @Nullable T inline) {
	public static final String DEFAULT_NAME = "default";

	public ConnectionConfigRef {
		Preconditions.checkArgument((name == null) != (inline == null),
									"exactly one of name and inline config must be given: name=%s, inline=%s",
									name, inline);
	}

	/**
	 * 전역 설정 이름을 가리키는 참조를 생성한다.
	 *
	 * @param <T>	연결 설정 타입.
	 * @param name	전역 설정 항목 이름.
	 * @return	참조 객체.
	 */
	public static <T> ConnectionConfigRef<T> ofName(String name) {
		Preconditions.checkNotNullArgument(name, "name is null");
		return new ConnectionConfigRef<>(name, null);
	}

	/**
	 * 인라인 연결 설정을 가리키는 참조를 생성한다.
	 *
	 * @param <T>		연결 설정 타입.
	 * @param config	연결 설정.
	 * @return	참조 객체.
	 */
	public static <T> ConnectionConfigRef<T> ofInline(T config) {
		Preconditions.checkNotNullArgument(config, "config is null");
		return new ConnectionConfigRef<>(null, config);
	}

	/**
	 * 연결 설정을 반환한다.
	 * <p>
	 * 인라인 설정이면 그대로 반환하고, 그렇지 않으면 {@code globalLookup}으로 전역 설정을 조회한다.
	 * 전역 설정은 조회할 때마다 파일에서 다시 읽으므로, 호출자는 결과를 보관하여 사용한다.
	 *
	 * @param globalLookup	전역 설정 이름으로 연결 설정을 조회하는 함수.
	 * @return	연결 설정.
	 */
	public T resolve(Function<String,T> globalLookup) {
		return (inline != null) ? inline : globalLookup.apply(name);
	}

	/**
	 * 로그 등에 출력할 문자열을 반환한다. 전역 설정 파일은 읽지 않는다.
	 *
	 * @param group			전역 설정 그룹 이름 (예: {@code jdbcs}).
	 * @param inlineToString	인라인 설정을 문자열로 바꾸는 함수. 비밀번호 등을 출력하지 않도록 주의한다.
	 * @return	{@code <group>.<name>} 또는 {@code inlineToString}의 결과.
	 */
	public String toDisplayString(String group, Function<T,String> inlineToString) {
		return (inline != null) ? inlineToString.apply(inline) : String.format("%s.%s", group, name);
	}

	/**
	 * 지정된 형태(이름 또는 객체) 그대로 {@code field}에 기록한다.
	 * <p>
	 * 인라인 설정의 값이 {@code null}인 필드는 기록하지 않는다. 일부 연결 설정 클래스는
	 * {@code null} 값을 받지 않으므로(예: {@code JdbcConfiguration.setDriverClassName}),
	 * {@code null}을 그대로 기록하면 기록한 설정을 다시 읽을 수 없다.
	 *
	 * @param gen	JSON generator.
	 * @param field	필드 이름.
	 * @throws IOException	기록이 실패한 경우.
	 */
	public void serialize(JsonGenerator gen, String field) throws IOException {
		if ( inline != null ) {
			JsonNode tree = MDTModelSerDe.getJsonMapper().valueToTree(inline);
			if ( tree instanceof ObjectNode obj ) {
				obj.properties().removeIf(ent -> ent.getValue().isNull());
			}
			gen.writeFieldName(field);
			gen.writeTree(tree);
		}
		else {
			gen.writeStringField(field, name);
		}
	}

	/**
	 * JSON 노드에서 연결 설정 참조를 읽는다.
	 *
	 * @param <T>			연결 설정 타입.
	 * @param jnode			AssetVariable 설정 JSON 노드.
	 * @param field			연결 설정 필드 이름 (문자열 또는 객체).
	 * @param legacyNameField	이전 형식의 전역 설정 이름 전용 필드 이름.
	 * @param configClass	연결 설정 클래스.
	 * @return	참조 객체. 두 필드가 모두 없으면 {@link #DEFAULT_NAME}을 가리킨다.
	 * @throws IOException	두 필드를 함께 사용했거나 필드 값의 형식이 잘못된 경우.
	 */
	public static <T> ConnectionConfigRef<T> load(JsonNode jnode, String field, String legacyNameField,
													Class<T> configClass) throws IOException {
		return load(JacksonUtils.getFieldOrNull(jnode, field), field,
					JacksonUtils.getFieldOrNull(jnode, legacyNameField), legacyNameField, configClass);
	}

	/**
	 * 연결 설정 필드와 이전 형식 필드의 값 노드로부터 연결 설정 참조를 읽는다.
	 * <p>
	 * {@link #load(JsonNode, String, String, Class)}와 같은 규칙을 따르며, {@code @JsonCreator}처럼
	 * 필드 값만 따로 받는 곳에서 사용한다.
	 *
	 * @param <T>			연결 설정 타입.
	 * @param configNode	연결 설정 필드 값 노드. {@code null}일 수 있다.
	 * @param field			연결 설정 필드 이름 (문자열 또는 객체).
	 * @param legacyNode	이전 형식 필드 값 노드. {@code null}일 수 있다.
	 * @param legacyNameField	이전 형식의 전역 설정 이름 전용 필드 이름.
	 * @param configClass	연결 설정 클래스.
	 * @return	참조 객체. 두 필드가 모두 없으면 {@link #DEFAULT_NAME}을 가리킨다.
	 * @throws IOException	두 필드를 함께 사용했거나 필드 값의 형식이 잘못된 경우.
	 */
	public static <T> ConnectionConfigRef<T> load(@Nullable JsonNode configNode, String field,
													@Nullable JsonNode legacyNode, String legacyNameField,
													Class<T> configClass) throws IOException {
		if ( configNode != null && configNode.isNull() ) {
			configNode = null;
		}
		if ( legacyNode != null && legacyNode.isNull() ) {
			legacyNode = null;
		}

		if ( configNode != null && legacyNode != null ) {
			throw new IOException(String.format("'%s' and '%s' cannot be used together", field, legacyNameField));
		}
		if ( legacyNode != null ) {
			if ( !legacyNode.isTextual() ) {
				throw new IOException(String.format("invalid '%s' value: %s (expected: a name)",
													legacyNameField, legacyNode));
			}
			return ofName(legacyNode.asText());
		}
		return fromNode(configNode, field, configClass);
	}

	/**
	 * 연결 설정 필드의 값 노드 하나를 참조로 해석한다.
	 * <p>
	 * 문자열이면 전역 설정 이름, 객체이면 인라인 연결 설정으로 해석하고, 값이 없으면({@code null} 또는 JSON
	 * {@code null}) {@link #DEFAULT_NAME}을 가리킨다. Jackson bean의 setter처럼 필드 값만 받는 곳에서 사용한다.
	 *
	 * @param <T>			연결 설정 타입.
	 * @param valueNode		필드 값 노드. {@code null}일 수 있다.
	 * @param field			오류 메시지에 쓸 필드 이름.
	 * @param configClass	연결 설정 클래스.
	 * @return	참조 객체.
	 * @throws IOException	값이 문자열도 객체도 아니거나, 객체를 연결 설정으로 읽지 못한 경우.
	 */
	public static <T> ConnectionConfigRef<T> fromNode(@Nullable JsonNode valueNode, String field,
														Class<T> configClass) throws IOException {
		if ( valueNode == null || valueNode.isNull() || valueNode.isMissingNode() ) {
			return ofName(DEFAULT_NAME);
		}
		if ( valueNode.isTextual() ) {
			return ofName(valueNode.asText());
		}
		if ( valueNode.isObject() ) {
			return ofInline(MDTModelSerDe.readValue(valueNode, configClass));
		}
		throw new IOException(String.format("invalid '%s' value: %s (expected: a name or an object)",
											field, valueNode));
	}
}
