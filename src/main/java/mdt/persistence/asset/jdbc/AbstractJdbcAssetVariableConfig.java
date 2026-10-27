package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.time.Duration;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import utils.Preconditions;
import utils.UnitUtils;
import utils.func.Optionals;
import utils.jdbc.JdbcConfiguration;
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
	private static final String FIELD_JDBC_CONFIG_NAME = "jdbcConfigName";	// 이전 형식
	private static final String FIELD_JDBC_CONFIG = "jdbcConfig";

	private ElementLocation m_elementLoc;
	private ConnectionConfigRef<JdbcConfiguration> m_jdbcConfigRef;
	private @Nullable Duration m_validPeriod;

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
	 * 캐시 유효 기간을 반환한다.
	 *
	 * @return 유효 기간. 설정되지 않은 경우 {@link Duration#ZERO}(캐시하지 않음).
	 */
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
	}
	
	@Override
	public String toString() {
		// 인라인 설정의 URL에는 비밀번호가 포함될 수 있으므로 출력하지 않는다.
		String jdbcKey = m_jdbcConfigRef.toDisplayString("jdbcs", cfg -> "(inline)");
		String validStr = Optionals.getOrElse(m_validPeriod, Duration.ZERO).toString();
		return String.format("%s, jdbc=%s, valid=%s", m_elementLoc, jdbcKey, validStr);
	}
}
