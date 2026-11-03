package mdt.persistence.asset;

import java.io.IOException;
import java.time.Duration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;

import org.jetbrains.annotations.Nullable;

import utils.UnitUtils;
import utils.json.JacksonUtils;
import utils.func.Nullables;

import mdt.ElementLocation;
import mdt.ElementLocations;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractAssetVariableConfig implements AssetVariableConfig {
	private ElementLocation m_elementLoc;
	private @Nullable Duration m_validPeriod;
	
	protected AbstractAssetVariableConfig() { }
	protected AbstractAssetVariableConfig(ElementLocation elementLoc, @Nullable Duration validPeriod) {
		Preconditions.checkArgument(elementLoc != null, "LocalElementKey is null");
		
		m_elementLoc = elementLoc;
		m_validPeriod = validPeriod;
	}
	
	@Override
	public ElementLocation getElementLocation() {
		return m_elementLoc;
	}
	
	public Duration getValidPeriod() {
		return Nullables.orElse(m_validPeriod, Duration.ZERO);
	}
	
	public String getValidPeriodString() {
		return Nullables.map(m_validPeriod, Duration::toString);
	}

	@Override
	public void serializeFields(JsonGenerator gen) throws IOException {
		gen.writeStringField("element", m_elementLoc.toStringExpr());
		Nullables.ifNotNullOrThrow(m_validPeriod, period -> gen.writeStringField("validPeriod", period.toString()));
	}
	
	/**
	 * JSON 노드로부터 {@link AbstractAssetVariableConfig} 객체를 생성한다.
	 * 
	 * @param jnode	JSON 노드
	 * @throws IOException	JSON 노드의 필드 값이 잘못된 경우 발생.
	 */
	protected void loadFields(JsonNode jnode) throws IOException {
		String elmLocExpr = JacksonUtils.getStringField(jnode, "element");
		m_elementLoc = ElementLocations.parseStringExpr(elmLocExpr);
		
		m_validPeriod = Nullables.map(JacksonUtils.getStringFieldOrNull(jnode, "validPeriod"),
										UnitUtils::parseDuration);
	}
	
	@Override
	public String toString() {
		String validStr = Nullables.orElse(m_validPeriod, Duration.ZERO).toString();
		return String.format("%s, valid=%s", m_elementLoc, validStr);
	}
}
