package mdt.persistence.mqtt;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.stream.FStream;

import mdt.ElementLocation;
import mdt.MDTGlobalConfigurations;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.PersistenceStack;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttPublishingPersistence extends PersistenceStack<MqttPublishingPersistenceConfig>
													implements Persistence<MqttPublishingPersistenceConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(MqttPublishingPersistence.class);

	MDTModelLookup m_lookup;
	private MqttBrokerConnectionConfig m_mqttBrokerConfig;
	MqttPublishingPersistenceConfig m_config;
	private PersistenceMqttClient m_mqttClient;
	
	public MqttPublishingPersistence() {
		setLogger(s_logger);
	}

	@Override
	public void init(CoreConfig coreConfig, MqttPublishingPersistenceConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		super.init(coreConfig, config, serviceContext);

		m_lookup = MDTModelLookup.getInstanceOrCreate(getBasePersistence());
		m_config = config;
		m_config.getPublishers().forEach(pub -> pub.getElementLocation().activate(m_lookup));
//		m_config.getSubscribers().forEach(sub -> sub.getElementLocation().activate(m_lookup));
		
		try {
			m_mqttBrokerConfig = MDTGlobalConfigurations.getMqttBrokerConnectionConfig("default");
		}
		catch ( Exception e ) {
			throw new ConfigurationInitializationException("Failed to read global configuration, cause=" + e);
		}
		m_mqttClient = new PersistenceMqttClient(this, m_mqttBrokerConfig);
		m_mqttClient.startAsync();
		
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("initialized {}, config={}", this, config);
		}
	}

	@Override
	public MqttPublishingPersistenceConfig asConfig() {
		return m_config;
	}

	@Override
	public void save(Submodel submodel) {
		getBasePersistence().save(submodel);

		String submodeId = submodel.getId();
		String submodelIdShort = m_lookup.getSubmodelIdShortFromSubmodelId(submodeId);
		String updateElmPath = "";
		
		for ( MqttElementPublisher publisher : m_config.getPublishers() ) {
			if ( !publisher.getElementLocation().getSubmodelIdShort().equals(submodelIdShort) ) {
				// 변경된 SubmodelElement의 Submodel이 등록된 Submodel과 다를 경우
				continue;
			}
			
			ElementLocation elmLoc = publisher.getElementLocation();
			
			String relPath = SubmodelUtils.toRelativeIdShortPath(updateElmPath, elmLoc.getElementPath());
			SubmodelElement elm = SubmodelUtils.traverse(submodel, relPath);
			ElementValue value = ElementValues.getValue(elm);
			
			m_mqttClient.publishMessage(publisher.getTopic(), value);
		}
	}

	@Override
	public void update(SubmodelElementIdentifier identifier, SubmodelElement submodelElement)
		throws ResourceNotFoundException {
		String submodeId = identifier.getSubmodelId();
		String submodelIdShort = m_lookup.getSubmodelIdShortFromSubmodelId(submodeId);
		String updateElmPath = identifier.getIdShortPath().toString();
		getLogger().debug("updating submodelElement: {}:{}", submodelIdShort, updateElmPath);
		
		// 변경된 SubmodelElement를 m_basePersistence을 통해 반영한다.
		getBasePersistence().update(identifier, submodelElement);
		
		for ( MqttElementPublisher publisher : m_config.getPublishers() ) {
			if ( !publisher.getElementLocation().getSubmodelIdShort().equals(submodelIdShort) ) {
				// 변경된 SubmodelElement의 Submodel이 등록된 Submodel과 다를 경우
				continue;
			}
			
			String pubElmPath = publisher.getElementLocation().getElementPath();
			
			SubmodelElement sme = null;
			if ( pubElmPath.equals(updateElmPath) ) {
				// 갱신된 SubmodelElement가 등록된 SubmodelElement와 동일한 경우
				sme = submodelElement;
			}
			else if ( pubElmPath.startsWith(updateElmPath) ) {	// area(updateElmPath) > area(pubElmPath)
				// 등록된 SubmodelElement이 갱신된 영역의 일부분인 경우
				String relPath = SubmodelUtils.toRelativeIdShortPath(updateElmPath, pubElmPath);
				sme = SubmodelUtils.traverse(submodelElement, relPath);
			}
			else if ( updateElmPath.startsWith(pubElmPath) ) {	// area(updateElmPath) < area(pubElmPath)
				// 등록된 SubmodelElement가 갱신된 영역을 포함하는 경우 
				sme = getBasePersistence().getSubmodelElement(publisher.getElementLocation().toIdentifier(),
															QueryModifier.DEFAULT);
			}
			
			if ( sme != null ) {
				getLogger().info("found a matching publisher: topic={}, path={}", publisher.getTopic(), pubElmPath);
				
				ElementValue value = ElementValues.getValue(sme);
				m_mqttClient.publishMessage(publisher.getTopic(), value);
			}
		}
	}
	
	@Override
	public String toString() {
		return String.format("MqttPublishingPersistence[broker=%s, publishers=%s]",
							m_mqttBrokerConfig.getBrokerUrl(),
							m_config.getPublishers());
	}
	
	@SuppressWarnings("unused")
	private List<MqttElementPublisher> findMatchingPublisherAll(String submodelIdShort, String elementPath) {
		return FStream.from(m_config.getPublishers())
					    .filter(pub -> {
					    	ElementLocation elmLoc = pub.getElementLocation();
					    	return elmLoc.getElementPath().startsWith(elementPath)
					    			&& elmLoc.getSubmodelIdShort().equals(submodelIdShort);
					    })
					    .toList();
	}
	
//	FOption<MqttElementSubscriber> findMatchingSubscriber(String topic) {
//		return FStream.from(m_config.getSubscribers())
//					    .findFirst(sub -> topic.equals(sub.getTopic()));
//	}
}
