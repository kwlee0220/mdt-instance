package mdt.persistence.asset;

import java.io.IOException;
import java.time.Duration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import mdt.ElementLocation;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonSerialize(using = AssetVariableConfigs.Serializer.class)
@JsonDeserialize(using = AssetVariableConfigs.Deserializer.class)
public interface AssetVariableConfig {
	/**
	 * 연결된 SubmodelElement의 키를 반환한다.
	 * 
	 * @return ElementLocation
	 */
	public ElementLocation getElementLocation();
	
	/**
	 * SubmodelElement의 값의 최대 유효기간을 반환한다.
	 * 
	 * @return 최대 유효 기간.
	 */
	public Duration getValidPeriod();

	public String getSerializationType();
	
	/**
	 * 주어진 {@link JsonGenerator}에 객체를 사용하여 본 {@code ConnectedElementConfig} 객체를
	 * JSON 형식으로 직렬화한다.
	 * 
	 * @param gen	Json serialization에 사용할 {@link JsonGenerator} 객체.
	 * @throws IOException	직렬화 실패시.
	 */
	public void serializeFields(JsonGenerator gen) throws IOException;
}
