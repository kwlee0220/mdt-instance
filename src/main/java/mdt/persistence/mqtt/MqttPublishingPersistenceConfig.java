package mdt.persistence.mqtt;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import utils.stream.FStream;

import mdt.persistence.PersistenceStackConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttPublishingPersistenceConfig extends PersistenceStackConfig<MqttPublishingPersistence> {
	private List<MqttElementPublisher> m_publishers;
//	private @Nullable List<MqttElementSubscriber> m_subscribers;

	public List<MqttElementPublisher> getPublishers() {
		return m_publishers;
	}
	
	public void setPublishers(List<MqttElementPublisher> publishers) {
		m_publishers = publishers;
	}
	
//	public List<MqttElementSubscriber> getSubscribers() {
//		return FOption.getOrElse(m_subscribers, List.of());
//	}
//	
//	public void setSubscribers(List<MqttElementSubscriber> subscribers) {
//		m_subscribers = subscribers;
//	}

	@Override
	protected void serializeFields(JsonGenerator gen) throws IOException {
		gen.writeArrayFieldStart("publishers");
		for ( MqttElementPublisher pubConf: m_publishers ) {
			gen.writeObject(pubConf);
		}
		gen.writeEndArray();
	}

	@Override
	protected void deserializeFields(JsonNode jnode) throws JacksonException {
		ObjectMapper mapper = new ObjectMapper();
		m_publishers = FStream.from(jnode.get("publishers").elements())
								.mapOrThrow(varConfNode -> mapper.treeToValue(varConfNode,
																			MqttElementPublisher.class))
								.toList();
	}
}
