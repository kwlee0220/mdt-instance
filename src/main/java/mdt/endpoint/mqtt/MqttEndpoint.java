package mdt.endpoint.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.dataformat.DeserializationException;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.http.serialization.HttpJsonApiDeserializer;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.model.ServiceSpecificationProfile;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.ElementValue;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.ElementValueParser;
import de.fraunhofer.iosb.ilt.faaast.service.starter.InitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.typing.TypeInfo;
import de.fraunhofer.iosb.ilt.faaast.service.util.ReferenceBuilder;

import com.google.common.collect.Lists;

import utils.InternalException;
import utils.KeyValue;
import utils.Throwables;

import mdt.ElementLocation;
import mdt.FaaastRuntime;
import mdt.MqttServicePool;
import mdt.client.support.MqttBrokerConfig;
import mdt.client.support.MqttService;
import mdt.persistence.MDTModelLookup;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttEndpoint implements Endpoint<MqttEndpointConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(MqttEndpoint.class);

	private FaaastRuntime m_faaast;
	private MqttEndpointConfig m_config;
	private MqttBrokerConfig m_brokerConfig;
	private MqttServicePool.Lease m_lease;		// 공유 연결 사용권 (start() 이후에만 존재)
	private final List<KeyValue<String,MqttSubscriber>> m_subscriptions = Lists.newArrayList();

	private final HttpJsonApiDeserializer m_apiDeserializer = new HttpJsonApiDeserializer();

	@Override
	public void init(CoreConfig coreConfig, MqttEndpointConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		m_config = config;
		
		m_faaast = FaaastRuntime.getOrCreate(serviceContext);
		
		try {
			m_brokerConfig = m_config.getMqttBrokerConfig();
			s_logger.info("Initialized MqttEndpoint: {}", m_brokerConfig);
		}
		catch ( InitializationException e ) {
			throw e;
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
	public List<ServiceSpecificationProfile> getProfiles() {
		return m_config.getProfiles();
	}
	
    @Override
    public void start() throws EndpointException {
		MDTModelLookup lookup = MDTModelLookup.getInstance();
		for ( MqttElementSubscriber sub: m_config.getSubscribers() ) {
			sub.getElementLocation().activate(lookup);
		}

		s_logger.info("Starting service: {}", this);

		// 같은 broker를 사용하는 MQTT AssetVariable들과 연결을 공유한다.
		// 공유 연결이 이미 맺어져 있을 수 있으므로, 요소 위치를 활성화한 뒤에 구독한다.
		m_lease = MqttServicePool.acquire(m_brokerConfig);
		for ( MqttElementSubscriber sub: m_config.getSubscribers() ) {
			MqttSubscriber subscriber = new MqttSubscriber(sub);
			m_lease.getService().subscribe(sub.getTopic(), 1, subscriber);
			m_subscriptions.add(KeyValue.of(sub.getTopic(), subscriber));
		}
    }

    @Override
    public void stop() {
    	if ( m_lease == null ) {
    		return;
    	}

    	// 공유 연결은 다른 사용자가 계속 사용할 수 있으므로, 이 endpoint의 구독을 먼저 해제한다.
    	for ( KeyValue<String,MqttSubscriber> subscription: m_subscriptions ) {
    		m_lease.getService().unsubscribe(subscription.key(), subscription.value());
    	}
    	m_subscriptions.clear();

    	m_lease.close();
    	m_lease = null;
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
			catch ( ResourceNotFoundException | PersistenceException neverHappens ) {
				throw new InternalException(neverHappens);
			}
		}
	}
}
