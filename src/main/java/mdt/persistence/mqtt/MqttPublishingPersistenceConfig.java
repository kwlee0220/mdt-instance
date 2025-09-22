package mdt.persistence.mqtt;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import mdt.persistence.MDTPersistenceStackConfig;
import mdt.persistence.PersistenceStackConfig;

import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttPublishingPersistenceConfig extends PersistenceStackConfig<MqttPublishingPersistence> {
	private final Core m_config;
	
	public MqttPublishingPersistenceConfig(Core config, PersistenceConfig<?> baseConfig) {
		super(baseConfig);
		
		m_config = config;
	}

	public List<MqttElementPublisher> getPublishers() {
		return m_config.getPublishers();
	}

	@JsonIncludeProperties({"publishers"})
	public static class Core implements MDTPersistenceStackConfig {
		private List<MqttElementPublisher> m_publishers = List.of();
	
		public List<MqttElementPublisher> getPublishers() {
			return m_publishers;
		}
		
		public void setPublishers(List<MqttElementPublisher> publishers) {
			m_publishers = publishers;
		}
	}
}
