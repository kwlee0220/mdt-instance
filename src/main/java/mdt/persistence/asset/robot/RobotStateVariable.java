package mdt.persistence.asset.robot;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Range;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import utils.Throwables;
import utils.thread.Guard;

import mdt.RobotTwinConfig;
import mdt.aas.DataType;
import mdt.aas.DataTypes;
import mdt.model.MDTModelSerDe;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.asset.AbstractAssetVariable;
import mdt.persistence.asset.AssetVariableException;
import mdt.persistence.asset.robot.RobotStateVariableConfig.SubPathToJsonPointer;
import mdt.robot.client.RobotTwinClient;


/**
 * 로봇 트윈(RESTful gateway)의 상태 변수 하나를 datasource로 사용하는 {@code AssetVariable} 구현.
 * <p>
 * mdt-robot-client의 {@link RobotTwinClient#readRaw(String)}로 변수의 최신 응답
 * ({@code GET {baseUrl}/api/v1/robot_twins/{twinId}/variables/{variable}})을 읽고,
 * 설정된 mapping에 따라 변수 값(JSON)의 각 부분을 대상 SubmodelElement의 하위 element에 반영한다.
 * 품질 판정은 이 클래스가 직접 하므로 품질이 낮아도 예외로 바꾸지 않는 {@code readRaw}를 쓴다.
 * {@link AbstractAssetVariable}이 제공하는 유효 기간 기반 캐시를 사용하기 때문에, 캐시가 만료된 경우에만
 * 로봇 트윈에 실제 요청을 보낸다. 공유되는 prototype에 대한 동시 접근을 막기 위해 읽기는
 * {@link Guard}로 직렬화된다.
 * <p>
 * 로봇 트윈은 상태 조회만 제공하므로 이 변수는 읽기 전용이다({@link #isUpdatable()}이 {@code false}).
 * <p>
 * 값의 신선도({@link RobotTwinVariable.Quality quality})는 다음과 같이 처리한다.
 * <ul>
 *   <li>{@code OK}가 아닌 경우 경고 로그를 남긴다. 설정에서
 *       {@link RobotStateVariableConfig#isRequireOkQuality()}가 {@code true}이면
 *       {@link AssetVariableException}을 발생시킨다.</li>
 *   <li>아직 수신된 값이 없는 경우({@code NO_DATA} 등 {@code value}가 {@code null})에는
 *       prototype을 갱신하지 않고 직전 값을 그대로 반환한다.</li>
 * </ul>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RobotStateVariable extends AbstractAssetVariable<RobotStateVariableConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(RobotStateVariable.class);

	private final RobotTwinClient m_twin;
	private final String m_variableUrl;		// 로그·오류 메시지용
	private final Guard m_guard = Guard.create();

	/**
	 * 주어진 설정으로 {@code RobotStateVariable}을 생성한다. 통신은 하지 않는다.
	 *
	 * @param config 로봇 트윈 접속 정보와 mapping을 포함하는 설정 객체.
	 * @throws IllegalArgumentException	로봇 트윈 base URL이 http(s) URL이 아닌 경우.
	 */
	public RobotStateVariable(RobotStateVariableConfig config) {
		super(config);

		RobotTwinConfig rtConfig = config.getRobotTwinConfig();
		m_twin = RobotTwinClient.builder()
								.baseUrl(rtConfig.getRobotTwinBaseUrl())
								.twinId(rtConfig.getTwinId())
								.build();
		m_variableUrl = m_twin.getTwinUrl() + "/variables/" + config.getVariable();

		setLogger(s_logger);
	}

	/**
	 * 로봇 트윈은 상태 조회만 제공하기 때문에 항상 {@code true}를 반환한다.
	 *
	 * @return 항상 {@code true}.
	 */
	@Override
	public boolean isReadable() {
		return true;
	}

	/**
	 * 로봇 트윈은 상태 변수 갱신을 제공하지 않기 때문에 항상 {@code false}를 반환한다.
	 *
	 * @return 항상 {@code false}.
	 */
	@Override
	public boolean isUpdatable() {
		return false;
	}

	/**
	 * 로봇 트윈에서 상태 변수의 최신 값을 읽어 prototype SubmodelElement에 반영하고 이를 반환한다.
	 * <p>
	 * 캐시가 만료된 경우에만 로봇 트윈에 요청을 보내며, 그렇지 않으면 캐시된 prototype을 사용한다.
	 * <p>
	 * 반환값은 {@link Guard}로 보호되는 임계 구역 안에서 만든 prototype의 복사본이므로,
	 * 호출자가 변경하거나 보관해도 캐시에 영향을 주지 않는다.
	 *
	 * @return prototype SubmodelElement의 복사본.
	 * @throws AssetVariableException 로봇 트윈 접속이 실패하거나 응답 해석에 실패한 경우.
	 */
	@Override
	public SubmodelElement read() throws AssetVariableException {
		m_guard.lock();
		try {
			Instant now = Instant.now();
			if ( isExpired(now) ) {
				loadOnBuffer(readRobotTwinVariable());

				// 로봇 트윈에서 새로 읽었기 때문에 refresh-time을 갱신한다.
				setLastRefreshTime(now);
			}

			// 캐시 자체를 반환하면 잠금을 푼 뒤 다른 쓰레드의 적재와 겹치므로 복사본을 반환한다.
			return SubmodelUtils.duplicate(m_prototype);
		}
		finally {
			m_guard.unlock();
		}
	}

	@Override
	public void update(SubmodelElement newElement) throws AssetVariableException {
		throw new AssetVariableException("update is not supported for RobotTwin AssetVariable: " + this);
	}

	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), getConfig());
	}

	/**
	 * 로봇 트윈에서 설정된 상태 변수를 조회한다.
	 *
	 * @return 조회된 상태 변수.
	 * @throws AssetVariableException 로봇 트윈 접속 또는 응답 해석이 실패한 경우.
	 */
	private RobotTwinVariable readRobotTwinVariable() throws AssetVariableException {
		try {
			JsonNode respNode = m_twin.readRaw(getConfig().getVariable());
			return MDTModelSerDe.getJsonMapper().treeToValue(respNode, RobotTwinVariable.class);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to read robot-twin variable: url=%s", m_variableUrl);
			throw new AssetVariableException(msg, cause);
		}
	}

	/**
	 * 조회된 상태 변수의 값을 prototype({@code m_prototype}) 버퍼에 반영한다.
	 * <p>
	 * 모든 mapping을 prototype의 복사본에 적용하고, 모두 성공한 경우에만 prototype을 교체한다.
	 * 일부 필드만 반영된 채로 남으면 다음 읽기에서 서로 다른 시점의 값이 섞여 반환되기 때문이다.
	 *
	 * @param variable 조회된 상태 변수.
	 * @throws AssetVariableException quality가 {@code OK}가 아니면서 {@code requireOkQuality}가
	 *                                설정된 경우, 또는 값 변환이 실패한 경우.
	 */
	private void loadOnBuffer(RobotTwinVariable variable) throws AssetVariableException {
		if ( !variable.isOk() ) {
			String msg = String.format("Robot-twin variable is not OK: url=%s, quality=%s, reason=%s",
										m_variableUrl, variable.getQuality(), variable.getReason());
			if ( getConfig().isRequireOkQuality() ) {
				throw new AssetVariableException(msg);
			}
			getLogger().warn(msg);
		}

		JsonNode valueNode = variable.getValue();
		if ( valueNode == null || valueNode.isNull() ) {
			// 아직 수신된 값이 없는 경우(NO_DATA 등)에는 직전 값을 유지한다.
			getLogger().warn("No value for robot-twin variable: url={}, quality={}",
							m_variableUrl, variable.getQuality());
			return;
		}

		SubmodelElement buffer = SubmodelUtils.duplicate(m_prototype);
		List<SubPathToJsonPointer> mappings = getConfig().getMappings();
		if ( mappings.isEmpty() ) {
			// Mapping이 설정되지 않은 경우에는 변수 값 전체를 대상 SubmodelElement에 반영한다.
			updateElement(buffer, valueNode);
		}
		else {
			for ( SubPathToJsonPointer mapping: mappings ) {
				JsonNode fieldNode = valueNode.at(mapping.jsonPointer());
				if ( fieldNode.isMissingNode() ) {
					getLogger().warn("No such field in robot-twin variable value: url={}, pointer={}",
									m_variableUrl, mapping.pointer());
					continue;
				}
				if ( fieldNode.isNull() ) {
					// 로봇 트윈은 NaN/Infinity를 null로 변환하여 보내기 때문에 이 경우는 갱신하지 않는다.
					getLogger().debug("Null field in robot-twin variable value: url={}, pointer={}",
									m_variableUrl, mapping.pointer());
					continue;
				}

				updateElement(traverse(buffer, mapping.subPath()), fieldNode);
			}
		}
		m_prototype = buffer;
	}

	/**
	 * 주어진 JSON 값으로 대상 SubmodelElement의 값을 갱신한다.
	 *
	 * @param target    갱신할 SubmodelElement.
	 * @param valueNode 갱신에 사용할 JSON 값.
	 * @throws AssetVariableException 값 변환이 실패한 경우.
	 */
	private void updateElement(SubmodelElement target, JsonNode valueNode) throws AssetVariableException {
		// 컬렉션·리스트도 내부에서 같은 숫자 변환을 쓰므로, 갱신 전에 구조를 따라 모든 값을 검사한다.
		checkValue(target.getIdShort(), target, valueNode);
		try {
			if ( target instanceof Property prop ) {
				// Property인 경우에는 선언된 valueType에 맞춰 JSON 값을 변환한다.
				DataType<?> type = DataTypes.fromAas4jDatatype(prop.getValueType());
				prop.setValue(type.toValueString(type.fromJsonNode(valueNode)));
			}
			else {
				// 그 외의 경우에는 MDT value 형식의 JSON으로 간주하고 갱신한다.
				ElementValues.update(target, valueNode);
			}
		}
		catch ( IOException | RuntimeException e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to update %s with value=%s", target.getIdShort(), valueNode);
			throw new AssetVariableException(msg, cause);
		}
	}

	/**
	 * 대상 SubmodelElement의 구조를 따라 내려가며 JSON 값의 종류를 검사한다.
	 * <p>
	 * Property는 {@link #checkJsonType}으로, 컬렉션은 같은 idShort의 필드를, 리스트는 같은 위치의 원소를,
	 * Range는 {@code min}·{@code max}를 검사한다. JSON 값이 없거나 {@code null}인 부분, 구조 자체가 맞지 않는
	 * 경우(컬렉션에 객체가 아닌 값 등)는 이후의 {@code ElementValues.update()}가 처리하도록 그대로 둔다.
	 *
	 * @param where     오류 메시지에 쓸 idShort 경로.
	 * @param target    검사 기준이 되는 SubmodelElement.
	 * @param valueNode 반영할 JSON 값.
	 * @throws AssetVariableException JSON 값의 종류나 범위가 맞지 않는 경우.
	 */
	private static void checkValue(String where, SubmodelElement target, @Nullable JsonNode valueNode)
		throws AssetVariableException {
		if ( valueNode == null || valueNode.isNull() ) {
			return;
		}
		if ( target instanceof Property prop ) {
			checkJsonType(where, prop.getValueType(), valueNode);
		}
		else if ( target instanceof SubmodelElementCollection smc && valueNode.isObject() ) {
			for ( SubmodelElement child: smc.getValue() ) {
				checkValue(where + "." + child.getIdShort(), child, valueNode.get(child.getIdShort()));
			}
		}
		else if ( target instanceof SubmodelElementList sml && valueNode.isArray() ) {
			List<SubmodelElement> elements = sml.getValue();
			for ( int i = 0; i < Math.min(elements.size(), valueNode.size()); ++i ) {
				checkValue(where + "[" + i + "]", elements.get(i), valueNode.get(i));
			}
		}
		else if ( target instanceof Range range && valueNode.isObject() ) {
			for ( String bound: new String[] { "min", "max" } ) {
				JsonNode boundNode = valueNode.get(bound);
				if ( boundNode != null && !boundNode.isNull() ) {
					checkJsonType(where + "." + bound, range.getValueType(), boundNode);
				}
			}
		}
	}

	/**
	 * JSON 값의 종류가 valueType에 맞는지 검사한다.
	 * <p>
	 * {@link DataTypes}의 숫자·boolean 변환은 Jackson의 {@code asDouble()}·{@code asLong()} 등을 그대로 써서
	 * {@code "sensor-error"} 같은 값을 0으로 바꾸므로, 변환 전에 막는다. 실수는 유한해야 한다(JSON의
	 * {@code 1e400}은 Jackson에서 Infinity가 된다). 다만 DECIMAL은 BigDecimal로 처리하므로 정수 값은
	 * double 범위를 넘어도 받는다. 검사하지 않는 valueType은 그대로 변환한다.
	 *
	 * @param where     오류 메시지에 쓸 idShort 경로.
	 * @param type      valueType.
	 * @param valueNode 반영할 JSON 값.
	 * @throws AssetVariableException JSON 값의 종류나 범위가 맞지 않는 경우.
	 */
	private static void checkJsonType(String where, DataTypeDefXsd type, JsonNode valueNode) throws AssetVariableException {
		boolean ok = switch ( type ) {
			case FLOAT -> valueNode.isNumber() && Math.abs(valueNode.doubleValue()) <= Float.MAX_VALUE;
			case DOUBLE -> valueNode.isNumber() && Double.isFinite(valueNode.doubleValue());
			// BigDecimal로 정확히 바꿀 수 있는 정수·BigDecimal 노드는 범위 제한이 없다. double로 읽힌 실수만 유한해야 한다.
			case DECIMAL -> valueNode.isIntegralNumber() || valueNode.isBigDecimal()
							|| (valueNode.isNumber() && Double.isFinite(valueNode.doubleValue()));
			case INTEGER -> valueNode.isIntegralNumber();
			case LONG -> valueNode.isIntegralNumber() && valueNode.canConvertToLong();
			case INT -> valueNode.isIntegralNumber() && valueNode.canConvertToInt();
			case SHORT -> valueNode.isIntegralNumber() && valueNode.canConvertToInt()
							&& valueNode.intValue() >= Short.MIN_VALUE && valueNode.intValue() <= Short.MAX_VALUE;
			case BYTE -> valueNode.isIntegralNumber() && valueNode.canConvertToInt()
							&& valueNode.intValue() >= Byte.MIN_VALUE && valueNode.intValue() <= Byte.MAX_VALUE;
			case BOOLEAN -> valueNode.isBoolean();
			default -> true;
		};
		if ( !ok ) {
			throw new AssetVariableException(String.format("JSON value does not match %s: element=%s, value=%s",
																type, where, valueNode));
		}
	}

	/**
	 * SubmodelElement 내에서 주어진 상대 경로의 하위 element를 찾는다.
	 *
	 * @param root    검색을 시작할 SubmodelElement.
	 * @param subPath 대상 SubmodelElement 기준의 상대 idShort 경로. 빈 문자열이면 {@code root} 자신.
	 * @return 검색된 SubmodelElement.
	 * @throws AssetVariableException 해당 경로의 element가 없는 경우.
	 */
	private SubmodelElement traverse(SubmodelElement root, String subPath) throws AssetVariableException {
		if ( subPath == null || subPath.length() == 0 ) {
			return root;
		}

		try {
			return SubmodelUtils.traverse(root, subPath);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to find sub-element: element=%s, subPath=%s",
										getElementLocation(), subPath);
			throw new AssetVariableException(msg, cause);
		}
	}
}
