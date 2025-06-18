package mdt.persistence.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import mdt.ElementLocation;
import mdt.ElementLocations;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "topic", "element" })
public class MqttElementPublisher {
	private final String m_topic;
	private final ElementLocation m_elementLoc;
	
	@JsonCreator
	public MqttElementPublisher(@JsonProperty("topic") String topic,
								@JsonProperty("element") String elementLocStr) {
		m_topic = topic;
		m_elementLoc = ElementLocations.parseStringExpr(elementLocStr);
	}
	
	public String getTopic() {
		return m_topic;
	}
	
	public ElementLocation getElementLocation() {
		return m_elementLoc;
	}
	
	@JsonProperty("element")
	public String getElementLocationExpr() {
		return m_elementLoc.toStringExpr();
	}
	
	@Override
	public String toString() {
		return String.format("%s -> %s", m_elementLoc.toStringExpr(), m_topic);
	}
}
