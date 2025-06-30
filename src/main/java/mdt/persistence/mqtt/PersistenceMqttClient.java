package mdt.persistence.mqtt;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.func.FOption;

import mdt.client.instance.MDTInstanceStatusSubscriber;
import mdt.client.support.AutoReconnectingMqttClient;
import mdt.model.sm.value.ElementValue;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class PersistenceMqttClient extends AutoReconnectingMqttClient {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTInstanceStatusSubscriber.class);

//	private final MqttPublishingPersistence m_mqttPublishingPersistence;
	private final MqttBrokerConnectionConfig  m_brokerConfig;
	private final int m_qos;
	
	PersistenceMqttClient(MqttPublishingPersistence persistence, MqttBrokerConnectionConfig brokerConfig)
		throws ConfigurationInitializationException {
		super(brokerConfig.getBrokerUrl(), null, brokerConfig.getReconnectTryInterval());
		
//		m_mqttPublishingPersistence = persistence;
		m_brokerConfig = brokerConfig;
		m_qos = 0;
		setLogger(s_logger);
	}
	
	public void publishMessage(String topic, ElementValue value) {
		try {
			MqttClient client = waitMqttClient(m_brokerConfig.getPublishTimeout());
			
			// value-string이 null인 경우는 빈 문자열로 대체한다.
			String jsonStr = FOption.getOrElse(value.toValueJsonString(), "");
			MqttMessage message = new MqttMessage(jsonStr.getBytes(StandardCharsets.UTF_8));
			message.setQos(m_qos);
			
			client.publish(topic, message);
		}
		catch ( Exception e ) {
			getLogger().warn("Failed to publish event: broker={}. topic={}, cause={}",
								m_brokerConfig.getBrokerUrl(), topic, ""+e);
		}
	}

	@Override
	public void messageArrived(String topic, MqttMessage msg) throws Exception {
		String json = new String(msg.getPayload(), StandardCharsets.UTF_8);
		
//		m_mqttPublishingPersistence.findMatchingSubscriber(topic)
//			.ifPresent(sub -> {
//				ElementLocation elmLoc = sub.getElementLocation();
//					
//				// MqttMessage에 있는 parameter 값은 String으로 되어 있으므로
//				// SubmodelElement에 맞는 형식으로 변환하기 위해 기존 parameter 값을
//				// SubmodelElement을 읽어와 이 값에 대해 update를 수행한다.
//				try {
//					SubmodelElementIdentifier identifier = elmLoc.toIdentifier();
//					SubmodelElement element = m_mqttPublishingPersistence.getSubmodelElement(identifier, QueryModifier.DEFAULT);
//					ElementValues.updateWithRawString(element, json);
//					m_mqttPublishingPersistence.update(identifier, element);
//				}
//				catch ( Exception e ) {
//					Throwable cause = Throwables.unwrapThrowable(e);
//					getLogger().error("Failed to update element, cause=" + cause);
//				}
//			});
	}

	@Override public void deliveryComplete(IMqttDeliveryToken token) { }
	
	@Override protected void mqttBrokerConnected(MqttClient client) throws Exception {
//		m_mqttPublishingPersistence.m_config.getSubscribers()
//			.forEach(sub -> {
//				try {
//					client.subscribe(sub.getTopic(), m_qos);
//					if ( getLogger().isInfoEnabled() ) {
//						getLogger().info("subscribe parameter-topic={}", sub.getTopic());
//					}
//				}
//				catch ( MqttException e ) {
//					getLogger().warn("Failed to subscribe topic={}, cause={}", sub.getTopic(), e);
//				}
//			});
	}
	@Override protected void mqttBrokerDisconnected() {
		s_logger.warn("Disconnected from MQTT broker: {}", m_brokerConfig.getBrokerUrl());
	}
}