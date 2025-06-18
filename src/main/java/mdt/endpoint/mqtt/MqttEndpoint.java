package mdt.endpoint.mqtt;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.InternalException;
import utils.Throwables;

import mdt.ElementLocation;
import mdt.FaaastRuntime;
import mdt.MDTGlobalConfigurations;
import mdt.client.support.MqttService;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.mqtt.MqttBrokerConfig;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.dataformat.DeserializationException;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.http.serialization.HttpJsonApiDeserializer;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.ElementValue;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.ElementValueParser;
import de.fraunhofer.iosb.ilt.faaast.service.typing.TypeInfo;
import de.fraunhofer.iosb.ilt.faaast.service.util.ReferenceBuilder;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttEndpoint implements Endpoint<MqttEndpointConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(MqttEndpoint.class);

	private FaaastRuntime m_faaast;
	private MqttEndpointConfig m_config;
	private MqttBrokerConfig m_brokerConfig;
	private MqttService m_mqttService;
	
	private final HttpJsonApiDeserializer m_apiDeserializer = new HttpJsonApiDeserializer();

	@Override
	public void init(CoreConfig coreConfig, MqttEndpointConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		m_config = config;
		
		m_faaast = new FaaastRuntime(serviceContext);
		
		try {
			m_brokerConfig = MDTGlobalConfigurations.getMqttConfig(m_config.getMqttConfig());
			
			m_mqttService = new MqttService(m_brokerConfig.getBrokerUrl(), "MqttEndpoint");
			for ( MqttElementSubscriber sub: m_config.getSubscribers() ) {
				m_mqttService.subscribe(sub.getTopic(), new MqttSubscriber(sub));
			}
		}
		catch ( Exception e ) {
			throw new ConfigurationInitializationException("Failed to read global configuration, cause=" + e);
		}
	}

	@Override
	public MqttEndpointConfig asConfig() {
		return m_config;
	}
	
    @Override
    public void start() throws EndpointException {
    	try {
			MDTModelLookup lookup = MDTModelLookup.getInstanceOrCreate(m_faaast.getSubmodels());
			for ( MqttElementSubscriber sub: m_config.getSubscribers() ) {
				sub.getElementLocation().activate(lookup);
			}
		}
		catch ( ConfigurationInitializationException e ) {
			throw new EndpointException("Failed to activate element location, cause=" + e);
		}
    
    	m_mqttService.startAsync();
    }

    @Override
    public void stop() {
    	m_mqttService.stopAsync();
    }
	
	@Override
	public String toString() {
		return "MqttEndpoint[broker=" + m_brokerConfig.getBrokerUrl() + "]";
	}
	
//	private static final int UNIT_SIZE = 1000;
	private class MqttSubscriber implements MqttService.Subscriber {
		private final MqttElementSubscriber m_mapping;
		private ElementValueParser<Object> m_valueParser = null;
//		private int m_count = 0;
//		private long m_started = -1;

		public MqttSubscriber(MqttElementSubscriber mapping) {
			m_mapping = mapping;
		}

		@Override
		public void onMessage(String topic, MqttMessage message) {
//			if ( m_started < 0 ) {
//				m_started = System.currentTimeMillis();
//			}
			
			String json = new String(message.getPayload(), StandardCharsets.UTF_8);

			// MqttMessage에 있는 parameter 값은 String으로 되어 있으므로
			// SubmodelElement에 맞는 형식으로 변환하기 위해 기존 parameter 값을
			// SubmodelElement을 읽어와 이 값에 대해 update를 수행한다.
			ElementLocation elmLoc = m_mapping.getElementLocation();
			if ( m_valueParser == null ) {
				m_valueParser = loadValueParser();
			}
			try {
				m_faaast.setSubmodelElementValueByPath(elmLoc.getSubmodelId(), elmLoc.getElementPath(),
														json, m_valueParser);
			}
			catch ( Exception e ) {
				Throwable cause = Throwables.unwrapThrowable(e);
				s_logger.error("Failed to update element, cause=" + cause);
			}
//			if ( ++m_count % UNIT_SIZE == 0 ) {
//				long elapsed = System.currentTimeMillis() - m_started;
//				System.out.printf("Received message: %s count=%d, elapsed=%.3fms%n",
//									Thread.currentThread().threadId(), m_count, elapsed/(float)UNIT_SIZE);
//				
//				m_started = -1;
//			}
		}
		
		private ElementValueParser<Object> loadValueParser() {
			try {
				ElementLocation elmLoc = m_mapping.getElementLocation();
				TypeInfo<?> typeInfo = m_faaast.getServiceContext()
												.getTypeInfo(new ReferenceBuilder()
										                            .submodel(elmLoc.getSubmodelId())
										                            .idShortPath(elmLoc.getElementPath())
										                            .build());
				return new ElementValueParser<Object>() {
                    @Override
                    public <U extends ElementValue> U parse(Object raw, Class<U> type) throws DeserializationException {
                        String rawString = raw.toString();
                        if (ElementValue.class.isAssignableFrom(type)) {
                            return m_apiDeserializer.readValue(rawString, typeInfo);
                        }
                        throw new DeserializationException(
                                String.format("error deserializing payload - invalid type '%s' (must be either instance of ElementValue or SubmodelElement",
                                        type.getSimpleName()));
                    }
                };
			}
			catch ( ResourceNotFoundException neverHappens ) {
				throw new InternalException(neverHappens);
			}
		}
	}
}
