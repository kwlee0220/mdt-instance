package mdt.endpoint.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import mdt.ElementLocation;
import mdt.ElementLocations;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "topic", "element" })
public class MqttElementSubscriber {
	private final String m_topic;
	private final ElementLocation m_elementLoc;
	
	@JsonCreator
	public MqttElementSubscriber(@JsonProperty("topic") String topic,
								@JsonProperty("element") String elementLocExpr) {
		Preconditions.checkArgument(topic != null, "topic is null");
		Preconditions.checkArgument(elementLocExpr != null, "element is null");
		
		m_topic = topic;
		m_elementLoc = ElementLocations.parseStringExpr(elementLocExpr);
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
