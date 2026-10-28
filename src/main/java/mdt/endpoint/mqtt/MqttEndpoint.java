package mdt.endpoint.mqtt;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.model.ServiceSpecificationProfile;
import de.fraunhofer.iosb.ilt.faaast.service.starter.InitializationException;

import com.google.common.collect.Lists;

import utils.KeyValue;

import mdt.FaaastRuntime;
import mdt.MqttServicePool;
import mdt.client.support.MqttBrokerConfig;
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
			MqttSubscriber subscriber = new MqttSubscriber(sub, m_faaast::newValueUpdater);
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
}
