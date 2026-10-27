package mdt.persistence.asset.robot;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

import utils.json.JacksonDeserializationException;
import utils.Preconditions;
import utils.json.JacksonUtils;

import mdt.ConnectionConfigRef;
import mdt.ElementLocation;
import mdt.MDTGlobalConfigurations;
import mdt.endpoint.rt.RobotTwinConfig;
import mdt.persistence.asset.AbstractAssetVariableConfig;
import mdt.persistence.asset.AssetVariableConfig;


/**
 * {@link RobotStateVariable}의 설정.
 * <p>
 * 로봇 트윈의 상태 변수 하나를 지정하고, 그 변수 값(JSON)의 일부를 대상 SubmodelElement의
 * 하위 element에 대응시키는 mapping을 기술한다. Mapping의 키는 대상 SubmodelElement 내부의
 * idShort 경로이고, 값은 로봇 트윈 변수 값에서 읽을 위치를 가리키는
 * <a href="https://datatracker.ietf.org/doc/html/rfc6901">JSON Pointer</a>이다.
 * <p>
 * 설정 예:
 * <pre>{@code
 * {
 *     "@type": "mdt:asset:robot-twin:state",
 *     "element": "Robot:DataInfo.Equipment.JointStates",
 *     "validPeriod": "500ms",
 *     "robotTwinConfig": { "robotTwinBaseUrl": "http://127.0.0.1:8801", "twinId": "panda01" },
 *     "variable": "joint_states",
 *     "requireOkQuality": true,
 *     "mappings": {
 *         "Joint1": "/position/panda_joint1",
 *         "Joint2": "/position/panda_joint2"
 *     }
 * }
 * }</pre>
 * {@code robotTwinConfig}는 전역 설정({@code robotTwins} 그룹)의 항목 이름(문자열)이거나 접속 정보 객체이다.
 * 생략하면 전역 설정의 {@code default} 항목을 사용한다. 이전 형식의 {@code robotTwinConfigName}도 받지만
 * {@code robotTwinConfig}와 함께 사용할 수는 없다({@link ConnectionConfigRef}).
 * {@code requireOkQuality}는 boolean이며 생략하면 {@code false}다.
 * Mapping의 값은 문자열 JSON Pointer여야 하며, 설정을 읽을 때 문법을 검사한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RobotStateVariableConfig extends AbstractAssetVariableConfig implements AssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:robot-twin:state";

	private static final String FIELD_ROBOT_TWIN_CONFIG_NAME = "robotTwinConfigName";	// 이전 형식
	private static final String FIELD_ROBOT_TWIN_CONFIG = "robotTwinConfig";
	private static final String FIELD_VARIABLE = "variable";
	private static final String FIELD_MAPPINGS = "mappings";
	private static final String FIELD_REQUIRE_OK_QUALITY = "requireOkQuality";

	private ConnectionConfigRef<RobotTwinConfig> m_robotTwinConfigRef;
	private String m_variable;
	private List<SubPathToJsonPointer> m_mappings = List.of();
	private @Nullable Boolean m_requireOkQuality = null;

	/**
	 * 대상 SubmodelElement 내부의 idShort 경로와 로봇 트윈 변수 값 내부의 JSON Pointer 사이의 대응.
	 *
	 * @param subPath 대상 SubmodelElement 기준의 상대 idShort 경로.
	 *                빈 문자열이면 대상 SubmodelElement 자신을 가리킨다.
	 * @param pointer 로봇 트윈 변수 값({@code value}) 기준의 JSON Pointer.
	 *                빈 문자열이면 변수 값 전체를 가리킨다.
	 * @throws IllegalArgumentException	{@code pointer}가 JSON Pointer 문법에 맞지 않는 경우.
	 * 									{@code ~} 뒤에는 {@code 0} 또는 {@code 1}만 올 수 있다(RFC 6901).
	 */
	public static record SubPathToJsonPointer(String subPath, String pointer) {
		// Jackson의 JsonPointer.compile()은 '~2'나 끝의 '~'를 문자 그대로 받아들이므로 따로 막는다.
		private static final Pattern INVALID_ESCAPE = Pattern.compile("~(?![01])");

		public SubPathToJsonPointer {
			Preconditions.checkNotNullArgument(subPath, "subPath is null");
			Preconditions.checkNotNullArgument(pointer, "pointer is null");
			Preconditions.checkArgument(!INVALID_ESCAPE.matcher(pointer).find(),
										"invalid JSON Pointer escape ('~' must be followed by 0 or 1): %s", pointer);
			JsonPointer.compile(pointer);
		}

		/**
		 * 컴파일한 JSON Pointer를 반환한다.
		 *
		 * @return JSON Pointer.
		 */
		public JsonPointer jsonPointer() {
			return JsonPointer.compile(pointer);
		}
	}

	private RobotStateVariableConfig() { }

	/**
	 * 주어진 설정으로 {@code RobotStateVariableConfig}를 생성한다.
	 * 접속 정보는 전역 설정의 {@code default} 항목에서 읽는다.
	 *
	 * @param elementLoc  연결할 SubmodelElement의 위치.
	 * @param validPeriod 읽어온 값의 유효 기간. {@code null}인 경우는 매번 새로 읽는다.
	 * @param variable    조회할 로봇 트윈 상태 변수의 이름.
	 * @param mappings    변수 값과 SubmodelElement 하위 element 사이의 대응.
	 */
	public RobotStateVariableConfig(ElementLocation elementLoc, @Nullable Duration validPeriod,
										String variable, List<SubPathToJsonPointer> mappings) {
		super(elementLoc, validPeriod);

		m_robotTwinConfigRef = ConnectionConfigRef.ofName(ConnectionConfigRef.DEFAULT_NAME);
		m_variable = variable;
		m_mappings = ( mappings != null ) ? mappings : List.of();
	}

	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}

	/**
	 * 전역 설정에서 로봇 트윈 접속 정보를 읽을 때 사용할 설정 이름을 반환한다.
	 *
	 * @return 전역 설정 이름. 접속 정보가 설정에 직접 기술된 경우는 {@code null}.
	 */
	public String getRobotTwinConfigName() {
		return m_robotTwinConfigRef.name();
	}
	
	/**
	 * 로봇 트윈 접속 정보를 반환한다.
	 * <p>
	 * 접속 정보가 설정에 직접 기술되어 있으면 이를 반환하고, 그렇지 않으면 전역 설정을 읽어 반환한다.
	 * 전역 설정은 호출할 때마다 파일에서 다시 읽으므로, 호출자는 결과를 보관하여 사용한다.
	 *
	 * @return 로봇 트윈 접속 정보.
	 */
	public RobotTwinConfig getRobotTwinConfig() {
		return m_robotTwinConfigRef.resolve(MDTGlobalConfigurations::getRobotTwinConfig);
	}

	/**
	 * 조회할 로봇 트윈 상태 변수의 이름을 반환한다.
	 *
	 * @return 상태 변수 이름.
	 */
	public String getVariable() {
		return m_variable;
	}

	/**
	 * 변수 값과 SubmodelElement 하위 element 사이의 대응을 반환한다.
	 *
	 * @return Mapping 리스트. 설정되지 않은 경우는 빈 리스트.
	 */
	public List<SubPathToJsonPointer> getMappings() {
		return m_mappings;
	}

	/**
	 * 변수의 quality가 {@code OK}가 아닌 경우 읽기를 실패시킬지 여부를 반환한다.
	 *
	 * @return {@code true}인 경우는 {@code OK}가 아닌 값을 읽으면 예외를 발생시키고,
	 *         {@code false}인 경우는 (기본값) 경고 로그만 남기고 값을 사용한다.
	 */
	public boolean isRequireOkQuality() {
		return m_requireOkQuality != null && m_requireOkQuality;
	}

	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);

		m_robotTwinConfigRef.serialize(gen, FIELD_ROBOT_TWIN_CONFIG);
		gen.writeStringField(FIELD_VARIABLE, m_variable);
		if ( m_requireOkQuality != null ) {
			gen.writeBooleanField(FIELD_REQUIRE_OK_QUALITY, m_requireOkQuality);
		}

		// 설정 예(클래스 Javadoc)와 deserializeFields()가 받는 형식과 같이 {subPath: pointer} 객체로 쓴다.
		gen.writeObjectFieldStart(FIELD_MAPPINGS);
		for ( SubPathToJsonPointer mapping: m_mappings ) {
			gen.writeStringField(mapping.subPath(), mapping.pointer());
		}
		gen.writeEndObject();
	}

	/**
	 * JSON 노드로부터 {@link RobotStateVariableConfig} 객체를 생성한다.
	 *
	 * @param jnode JSON 노드.
	 * @return 생성된 {@link RobotStateVariableConfig} 객체.
	 * @throws IOException JSON 노드의 필드값이 잘못된 경우.
	 */
	public static RobotStateVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		RobotStateVariableConfig config = new RobotStateVariableConfig();
		config.loadFields(jnode);

		config.m_robotTwinConfigRef = ConnectionConfigRef.load(jnode, FIELD_ROBOT_TWIN_CONFIG,
																FIELD_ROBOT_TWIN_CONFIG_NAME, RobotTwinConfig.class);
		
		config.m_variable = JacksonUtils.getStringField(jnode, FIELD_VARIABLE);

		JsonNode requireOkNode = JacksonUtils.getFieldOrNull(jnode, FIELD_REQUIRE_OK_QUALITY);
		if ( requireOkNode != null && !requireOkNode.isNull() ) {
			if ( !requireOkNode.isBoolean() ) {
				throw new JacksonDeserializationException(String.format("invalid '%s' field (boolean expected): %s",
																		FIELD_REQUIRE_OK_QUALITY, requireOkNode));
			}
			config.m_requireOkQuality = requireOkNode.booleanValue();
		}

		JsonNode mappingsNode = JacksonUtils.getFieldOrNull(jnode, FIELD_MAPPINGS);
		if ( mappingsNode == null || mappingsNode.isNull() ) {
			// mapping이 없으면 변수 값 전체를 대상 SubmodelElement에 반영한다.
			config.m_mappings = List.of();
		}
		else if ( mappingsNode.isObject() ) {
			config.m_mappings = new ArrayList<>();
			for ( Map.Entry<String, JsonNode> mappingNode: mappingsNode.properties() ) {
				config.m_mappings.add(toMapping(mappingNode.getKey(), mappingNode.getValue()));
			}
		}
		else if ( mappingsNode.isArray() ) {
			// 이전 버전의 serializeFields()가 [{"subPath": ..., "pointer": ...}, ...] 형식으로 저장한
			// 설정도 읽을 수 있도록 배열 형식을 함께 받는다.
			config.m_mappings = new ArrayList<>();
			for ( JsonNode mappingNode: mappingsNode ) {
				String subPath = JacksonUtils.getStringField(mappingNode, "subPath");
				config.m_mappings.add(toMapping(subPath, mappingNode.get("pointer")));
			}
		}
		else {
			// 형식이 잘못된 mapping을 빈 목록으로 두면, 변수 값 전체가 반영되어 잘못된 값이 기록된다.
			throw new JacksonDeserializationException(String.format("invalid '%s' field (object expected): %s",
																	FIELD_MAPPINGS, mappingsNode));
		}

		return config;
	}

	/**
	 * Mapping 하나를 검증하여 만든다. {@code asText()}로 읽으면 객체·숫자가 빈 문자열(변수 값 전체)이나
	 * 엉뚱한 포인터가 되므로, 문자열이고 JSON Pointer 문법에 맞는 경우만 받는다.
	 */
	private static SubPathToJsonPointer toMapping(String subPath, @Nullable JsonNode pointerNode)
		throws JacksonDeserializationException {
		if ( pointerNode == null || !pointerNode.isTextual() ) {
			throw new JacksonDeserializationException(String.format(
					"invalid '%s' pointer for '%s' (JSON Pointer string expected): %s", FIELD_MAPPINGS, subPath, pointerNode));
		}
		try {
			return new SubPathToJsonPointer(subPath, pointerNode.textValue());
		}
		catch ( IllegalArgumentException e ) {
			throw new JacksonDeserializationException(String.format(
					"invalid '%s' pointer for '%s': %s", FIELD_MAPPINGS, subPath, pointerNode.textValue()), e);
		}
	}

	@Override
	public String toString() {
		// 전역 설정 파일을 읽지 않도록 접속 정보를 해석하지 않고 설정에 기술된 내용만 사용한다.
		String twin = m_robotTwinConfigRef.toDisplayString("robotTwins", RobotTwinConfig::getTwinId);
		return String.format("%s, twin=%s, variable=%s", super.toString(), twin, m_variable);
	}
}
