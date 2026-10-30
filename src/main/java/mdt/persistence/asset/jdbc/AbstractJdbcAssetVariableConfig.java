package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.Preconditions;
import utils.UnitUtils;
import utils.func.Optionals;
import utils.jdbc.JdbcConfiguration;
import utils.json.JacksonDeserializationException;
import utils.json.JacksonUtils;

import mdt.ConnectionConfigRef;
import mdt.ElementLocation;
import mdt.ElementLocations;
import mdt.MDTGlobalConfigurations;
import mdt.persistence.asset.AssetVariableConfig;


/**
 * JDBC를 datasource로 사용하는 {@code AssetVariable} 설정의 추상 기반 클래스.
 * <p>
 * 모든 JDBC 기반 AssetVariable 설정이 공통으로 갖는 항목을 보관한다.
 * <ul>
 *   <li>element 위치({@link ElementLocation}) — 연결될 SubmodelElement의 위치.</li>
 *   <li>JDBC 접속 설정({@code jdbcConfig}) — 전역 설정({@code jdbcs})의 항목 이름(문자열)이나
 *       인라인 {@link JdbcConfiguration}(객체) 중 하나. 생략하면 {@code default}이다.
 *       이전 형식의 {@code jdbcConfigName}도 받지만 {@code jdbcConfig}와 함께 사용할 수는 없다
 *       ({@link ConnectionConfigRef}).</li>
 *   <li>유효 기간 — 읽은 값의 캐시 TTL. 미설정 시 {@link Duration#ZERO}(캐시하지 않음).</li>
 * </ul>
 * JDBC 접속 설정은 인라인 설정이 있으면 그것을, 없으면 이름으로 전역 설정
 * ({@link MDTGlobalConfigurations})을 조회하여 사용한다({@link #getJdbcConfig()}).
 * <p>
 * 하위 클래스는 {@link #serializeFields(JsonGenerator)}/{@link #loadFields(JsonNode)}를 호출하여
 * 공통 필드의 직렬화/역직렬화를 위임하고, 자신만의 필드를 추가로 처리한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractJdbcAssetVariableConfig implements AssetVariableConfig {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractJdbcAssetVariableConfig.class);
	private static final String FIELD_JDBC_CONFIG_NAME = "jdbcConfigName";	// 이전 형식
	private static final String FIELD_JDBC_CONFIG = "jdbcConfig";
	/** 모든 JDBC AssetVariable 설정이 공통으로 받는 필드. */
	private static final String FIELD_QUERY_TIMEOUT = "queryTimeout";
	private static final Set<String> COMMON_FIELDS = Set.of("@type", "element", "validPeriod",
																FIELD_JDBC_CONFIG, FIELD_JDBC_CONFIG_NAME,
																FIELD_QUERY_TIMEOUT);
	/** {@code queryTimeout}이 지정되지 않은 경우의 SQL 실행 제한 시간. */
	public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(30);

	private ElementLocation m_elementLoc;
	private ConnectionConfigRef<JdbcConfiguration> m_jdbcConfigRef;
	private @Nullable Duration m_validPeriod;
	private @Nullable Duration m_queryTimeout;	// SQL 실행 제한 시간. null이면 DEFAULT_QUERY_TIMEOUT.

	/** 역직렬화 전용 기본 생성자. */
	protected AbstractJdbcAssetVariableConfig() { }

	/**
	 * 주어진 항목으로 설정을 생성한다.
	 *
	 * @param elementLoc		연결할 SubmodelElement의 위치. {@code null}이면 안 된다.
	 * @param jdbcConfigName	사용할 JDBC 접속 설정의 이름(key). {@code null}이면 {@code default}를 사용한다.
	 * @param validPeriod		캐시 유효 기간. {@code null}이면 캐시하지 않는다({@link Duration#ZERO}).
	 */
	protected AbstractJdbcAssetVariableConfig(ElementLocation elementLoc, @Nullable String jdbcConfigName,
												@Nullable Duration validPeriod) {
		Preconditions.checkNotNullArgument(elementLoc, "elementLoc is null");

		m_elementLoc = elementLoc;
		m_jdbcConfigRef = ConnectionConfigRef.ofName(Optionals.getOrElse(jdbcConfigName,
																		ConnectionConfigRef.DEFAULT_NAME));
		m_validPeriod = validPeriod;
	}

	@Override
	public ElementLocation getElementLocation() {
		return m_elementLoc;
	}

	/**
	 * 사용할 JDBC 접속 설정을 반환한다.
	 * <p>
	 * 인라인 {@link JdbcConfiguration}이 설정되어 있으면 그것을 반환하고,
	 * 그렇지 않으면 설정된 이름(key)으로 전역 설정({@link MDTGlobalConfigurations})을 조회하여 반환한다.
	 *
	 * @return JDBC 접속 설정.
	 */
	public JdbcConfiguration getJdbcConfig() {
		return m_jdbcConfigRef.resolve(MDTGlobalConfigurations::getJdbcConfig);
	}
	
	/**
	 * JDBC 접속 설정의 참조(전역 설정 이름 또는 인라인 설정)를 반환한다.
	 *
	 * @return	JDBC 접속 설정 참조.
	 */
	public ConnectionConfigRef<JdbcConfiguration> getJdbcConfigRef() {
		return m_jdbcConfigRef;
	}

	/**
	 * SQL 하나의 실행 제한 시간을 반환한다.
	 *
	 * @return 제한 시간. 설정되지 않은 경우 기본값(30초).
	 */
	public Duration getQueryTimeout() {
		return (m_queryTimeout != null) ? m_queryTimeout : DEFAULT_QUERY_TIMEOUT;
	}
	
	public Duration getValidPeriod() {
		return (m_validPeriod != null) ? m_validPeriod : Duration.ZERO;
	}

	/**
	 * 캐시 유효 기간을 문자열로 반환한다.
	 *
	 * @return 유효 기간 문자열. 설정되지 않은 경우 {@code null}.
	 */
	public String getValidPeriodString() {
		return Optionals.map(m_validPeriod, Duration::toString);
	}

	/**
	 * 공통 필드(element 위치, JDBC 접속 설정, 유효 기간)를 주어진 {@link JsonGenerator}로 직렬화한다.
	 * <p>
	 * JDBC 접속 설정은 지정된 형태(이름 또는 객체) 그대로 {@code jdbcConfig}에 기록하고,
	 * 유효 기간은 설정된 경우에만 기록한다.
	 *
	 * @param gen	직렬화에 사용할 JsonGenerator.
	 * @throws IOException	직렬화가 실패한 경우.
	 */
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		gen.writeStringField("element", m_elementLoc.toStringExpr());
		m_jdbcConfigRef.serialize(gen, FIELD_JDBC_CONFIG);
		Optionals.acceptThrow(m_validPeriod, period -> gen.writeStringField("validPeriod", period.toString()));
		Optionals.acceptThrow(m_queryTimeout, timeout -> gen.writeStringField(FIELD_QUERY_TIMEOUT, timeout.toString()));
	}

	/**
	 * 주어진 JSON 노드로부터 공통 필드를 읽어 이 설정 객체에 채운다.
	 * <p>
	 * JDBC 접속 설정이 없으면 전역 설정의 {@code default} 항목을 사용한다.
	 *
	 * @param jnode	JSON 노드.
	 * @throws IOException	역직렬화가 실패한 경우.
	 */
	protected void loadFields(JsonNode jnode) throws IOException {
		String elmLocExpr = JacksonUtils.getStringField(jnode, "element");
		m_elementLoc = ElementLocations.parseStringExpr(elmLocExpr);
		
		m_jdbcConfigRef = ConnectionConfigRef.load(jnode, FIELD_JDBC_CONFIG, FIELD_JDBC_CONFIG_NAME,
													JdbcConfiguration.class);
		m_validPeriod = Optionals.map(JacksonUtils.getStringFieldOrNull(jnode, "validPeriod"), UnitUtils::parseDuration);
		m_queryTimeout = Optionals.map(JacksonUtils.getStringFieldOrNull(jnode, FIELD_QUERY_TIMEOUT),
										UnitUtils::parseDuration);
		if ( m_queryTimeout != null && (m_queryTimeout.isNegative() || m_queryTimeout.isZero()) ) {
			throw new JacksonDeserializationException(String.format("'%s' must be positive: %s",
																	FIELD_QUERY_TIMEOUT, m_queryTimeout));
		}
	}
	
	/**
	 * 설정에 이 타입이 사용하지 않는 필드가 있으면 경고 로그를 남긴다.
	 * <p>
	 * 철자가 틀린 필드나 다른 타입의 필드는 조용히 무시되어 설정 실수가 드러나지 않으므로 알린다.
	 * 기존 설정과의 호환을 위해 거부하지는 않는다.
	 *
	 * @param jnode			설정 JSON 노드.
	 * @param typeFields	이 타입이 공통 필드 외에 받는 필드들.
	 */
	protected static void warnUnknownFields(JsonNode jnode, Set<String> typeFields) {
		List<String> unknown = new ArrayList<>();
		for ( Map.Entry<String,JsonNode> field: jnode.properties() ) {
			String name = field.getKey();
			if ( !COMMON_FIELDS.contains(name) && !typeFields.contains(name) ) {
				unknown.add(name);
			}
		}
		if ( !unknown.isEmpty() ) {
			s_logger.warn("ignored unknown fields in JDBC AssetVariable config: type={}, element={}, fields={}",
							JacksonUtils.getStringFieldOrNull(jnode, "@type"),
							JacksonUtils.getStringFieldOrNull(jnode, "element"), unknown);
		}
	}

	/**
	 * 비어 있지 않은 문자열 필드를 읽는다.
	 *
	 * @param jnode	설정 JSON 노드.
	 * @param field	필드 이름.
	 * @param where	오류 메시지에 쓸 위치 설명.
	 * @return	필드 값.
	 * @throws JacksonDeserializationException	필드가 없거나 문자열이 아니거나 비어 있는 경우.
	 */
	protected static String requireText(JsonNode jnode, String field, String where) {
		JsonNode node = jnode.get(field);
		if ( node == null || !node.isTextual() || node.asText().isBlank() ) {
			throw new JacksonDeserializationException(String.format("'%s' must be a non-blank string in %s: %s",
																	field, where, jnode));
		}
		return node.asText();
	}

	/**
	 * 열거형 값을 갖는 문자열 필드를 읽는다. 값은 대소문자를 구분하지 않는다.
	 *
	 * @param <E>			열거형 타입.
	 * @param enumCls		열거형 클래스.
	 * @param jnode			설정 JSON 노드.
	 * @param field			필드 이름.
	 * @param defaultValue	필드가 없을 때 사용할 값.
	 * @return	필드 값.
	 * @throws JacksonDeserializationException	값이 열거형 상수가 아닌 경우.
	 */
	protected static <E extends Enum<E>> E parseEnum(Class<E> enumCls, JsonNode jnode, String field,
														E defaultValue) {
		String str = JacksonUtils.getStringFieldOrNull(jnode, field);
		if ( str == null ) {
			return defaultValue;
		}
		try {
			return Enum.valueOf(enumCls, str.trim().toUpperCase(Locale.ROOT));
		}
		catch ( IllegalArgumentException e ) {
			throw new JacksonDeserializationException(String.format("invalid '%s': %s (expected one of %s)",
															field, str, Arrays.toString(enumCls.getEnumConstants())));
		}
	}

	@Override
	public String toString() {
		// 인라인 설정의 URL에는 비밀번호가 포함될 수 있으므로 출력하지 않는다.
		String jdbcKey = m_jdbcConfigRef.toDisplayString("jdbcs", cfg -> "(inline)");
		String validStr = Optionals.getOrElse(m_validPeriod, Duration.ZERO).toString();
		return String.format("%s, jdbc=%s, valid=%s", m_elementLoc, jdbcKey, validStr);
	}
}
