package mdt.persistence;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import mdt.persistence.mqtt.MqttPublishingPersistenceConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="@type")
@JsonSubTypes({
	@JsonSubTypes.Type(value=MqttPublishingPersistenceConfig.Core.class, name="mqtt"),
//	@JsonSubTypes.Type(value=TimeSeriesPersistenceStackConfig.Core.class, name="timeseries"),
//	@JsonSubTypes.Type(value=AssertVariableBasedPersistenceConfig.Core.class, name="asset"),
	@JsonSubTypes.Type(value=ConcurrentPersistenceConfig.Core.class, name="concurrent"),
})
public interface MDTPersistenceStackConfig {
}
