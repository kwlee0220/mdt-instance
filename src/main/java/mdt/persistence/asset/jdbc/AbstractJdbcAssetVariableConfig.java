package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.time.Duration;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;

import utils.UnitUtils;
import utils.func.FOption;
import utils.jdbc.JdbcConfiguration;
import utils.json.JacksonUtils;

import mdt.ElementLocation;
import mdt.ElementLocations;
import mdt.MDTGlobalConfigurations;
import mdt.model.MDTModelSerDe;
import mdt.persistence.asset.AssetVariableConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractJdbcAssetVariableConfig implements AssetVariableConfig {
	private static final String DEFAULT_JDBC_CONFIG_KEY = "default";
	private static final String FIELD_JDBC_CONFIG_NAME = "jdbcConfigName";
	private static final String FIELD_JDBC_CONFIG = "jdbcConfig";
	
	private ElementLocation m_elementLoc;
	private String m_jdbcConfigName;
	private JdbcConfiguration m_jdbcConfig;
	private @Nullable Duration m_validPeriod;
	
	protected AbstractJdbcAssetVariableConfig() { }
	protected AbstractJdbcAssetVariableConfig(ElementLocation elementLoc, String jdbcConfig,
												@Nullable Duration validPeriod) {
		Preconditions.checkArgument(elementLoc != null, "LocalElementKey is null");
		
		m_elementLoc = elementLoc;
		m_jdbcConfigName = jdbcConfig;
		m_validPeriod = validPeriod;
	}
	
	@Override
	public ElementLocation getElementLocation() {
		return m_elementLoc;
	}
	
	public JdbcConfiguration getJdbcConfig() {
		if ( m_jdbcConfig == null ) {
			return MDTGlobalConfigurations.getJdbcConfig(m_jdbcConfigName);
		}
		else {
			return m_jdbcConfig;
		}
	}
	
	public Duration getValidPeriod() {
		return FOption.getOrElse(m_validPeriod, Duration.ZERO);
	}
	
	public String getValidPeriodString() {
		return FOption.map(m_validPeriod, Duration::toString);
	}

	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		gen.writeStringField("element", m_elementLoc.toStringExpr());
		FOption.acceptOrThrow(m_jdbcConfigName, name -> gen.writeStringField(FIELD_JDBC_CONFIG_NAME, name));
		FOption.acceptOrThrow(m_jdbcConfig, cfg -> gen.writeObjectField(FIELD_JDBC_CONFIG_NAME, cfg));
		FOption.acceptOrThrow(m_validPeriod, period -> gen.writeStringField("validPeriod", period.toString()));
	}
	
	/**
	 * JSON 노드로부터 {@link AbstractJdbcAssetVariableConfig} 객체를 생성한다.
	 * <p>
	 * 본 메소드는 {@link AssetVariableConfig.Deserializer}에서 호출된다.
	 * 
	 * @param jnode	JSON 노드
	 * @return	생성된 {@link AbstractJdbcAssetVariableConfig} 객체.
	 * @throws IOException 
	 */
	protected void loadFields(JsonNode jnode) throws IOException {
		String elmLocExpr = JacksonUtils.getStringField(jnode, "element");
		m_elementLoc = ElementLocations.parseStringExpr(elmLocExpr);
		
		m_jdbcConfigName = FOption.getOrElse(JacksonUtils.getStringFieldOrNull(jnode, FIELD_JDBC_CONFIG_NAME),
											DEFAULT_JDBC_CONFIG_KEY);
		JsonNode configNode = JacksonUtils.getFieldOrNull(jnode, FIELD_JDBC_CONFIG);
		m_jdbcConfig = MDTModelSerDe.readValue(configNode, JdbcConfiguration.class);
		m_validPeriod = FOption.map(JacksonUtils.getStringFieldOrNull(jnode, "validPeriod"), UnitUtils::parseDuration);
	}
	
	@Override
	public String toString() {
		String jdbcKey = FOption.getOrElse(m_jdbcConfigName, "");
		String validStr = FOption.getOrElse(m_validPeriod, Duration.ZERO).toString();
		return String.format("%s, jdbc=%s, valid=%s", m_elementLoc, jdbcKey, validStr);
	}
}
