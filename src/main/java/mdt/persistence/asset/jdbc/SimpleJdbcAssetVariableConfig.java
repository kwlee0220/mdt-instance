package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.time.Duration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import org.jetbrains.annotations.Nullable;

import utils.func.Optionals;
import utils.json.JacksonUtils;

import mdt.ElementLocation;


/**
 * {@link SimpleJdbcAssetVariable}의 설정.
 * <p>
 * 단일 SubmodelElement를 스칼라 값으로 읽고 쓰는 데 사용할 SQL 질의를 보관한다.
 * <ul>
 *   <li>{@code readQuery} — 한 행·한 컬럼의 값을 반환하는 {@code SELECT} (필수).</li>
 *   <li>{@code updateQuery} — 단일 파라미터를 받는 갱신 질의 (선택, 없으면 읽기 전용).</li>
 * </ul>
 * element 위치, JDBC 접속 설정, 유효 기간 등 공통 항목은 {@link AbstractJdbcAssetVariableConfig}이 제공한다.
 * 직렬화 식별자는 {@value #SERIALIZATION_TYPE}이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SimpleJdbcAssetVariableConfig extends AbstractJdbcAssetVariableConfig {
	public static final String SERIALIZATION_TYPE = "mdt:asset:jdbc:simple";
	static final String FIELD_READ_QUERY = "readQuery";
	static final String FIELD_UPDATE_QUERY = "updateQuery";

	private String m_readQuery;
	private @Nullable String m_updateQuery;

	private SimpleJdbcAssetVariableConfig()  { }

	/**
	 * 주어진 항목으로 {@code SimpleJdbcAssetVariableConfig}을 생성한다.
	 *
	 * @param elementLoc	연결할 SubmodelElement의 위치.
	 * @param jdbcConfigKey	사용할 JDBC 접속 설정의 키. {@code null}이면 기본 설정을 사용한다.
	 * @param validPeriod	캐시 유효 기간. {@code null}이면 캐시하지 않는다({@link Duration#ZERO}).
	 * @param readQuery		한 행·한 컬럼의 값을 반환하는 조회 질의.
	 * @param updateQuery	단일 파라미터를 받는 갱신 질의. {@code null}이면 읽기 전용.
	 */
	public SimpleJdbcAssetVariableConfig(ElementLocation elementLoc, @Nullable String jdbcConfigKey,
											@Nullable Duration validPeriod,
											String readQuery, @Nullable String updateQuery) {
		super(elementLoc, jdbcConfigKey, validPeriod);

		m_readQuery = readQuery;
		m_updateQuery = updateQuery;
	}

	/**
	 * 조회 질의를 반환한다.
	 *
	 * @return 조회 질의.
	 */
	public String getReadQuery() {
		return m_readQuery;
	}

	/**
	 * 갱신 질의를 반환한다.
	 *
	 * @return 갱신 질의. 설정되지 않은 경우 {@code null}.
	 */
	public String getUpdateQuery() {
		return m_updateQuery;
	}

	/**
	 * 이 설정의 직렬화 식별자({@value #SERIALIZATION_TYPE})를 반환한다.
	 *
	 * @return 직렬화 식별자.
	 */
	@Override
	public String getSerializationType() {
		return SERIALIZATION_TYPE;
	}

	/**
	 * 이 설정의 필드들을 주어진 {@link JsonGenerator}로 직렬화한다.
	 * <p>
	 * 공통 필드는 상위 클래스가 직렬화하고, 여기서는 조회/갱신 질의를 추가로 기록한다.
	 * 갱신 질의는 설정된 경우에만 기록된다.
	 *
	 * @param gen	직렬화에 사용할 JsonGenerator.
	 * @throws IOException	직렬화가 실패한 경우.
	 */
	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		super.serializeFields(gen);
		gen.writeStringField(FIELD_READ_QUERY, m_readQuery);
		Optionals.acceptThrow(m_updateQuery, sql -> gen.writeStringField(FIELD_UPDATE_QUERY, sql));
	}
	
	/**
	 * JSON 노드로부터 {@link SimpleJdbcAssetVariableConfig} 객체를 생성한다.
	 * 
	 * @param jnode	JSON 노드
	 * @return	생성된 {@link SimpleJdbcAssetVariableConfig} 객체.
	 * @throws IOException 
	 */
	public static SimpleJdbcAssetVariableConfig deserializeFields(JsonNode jnode) throws IOException {
		SimpleJdbcAssetVariableConfig config = new SimpleJdbcAssetVariableConfig();
		config.loadFields(jnode);
		
		config.m_readQuery = JacksonUtils.getStringField(jnode, FIELD_READ_QUERY);
		config.m_updateQuery = JacksonUtils.getStringFieldOrNull(jnode, FIELD_UPDATE_QUERY);
		
		return config;
	}
}
