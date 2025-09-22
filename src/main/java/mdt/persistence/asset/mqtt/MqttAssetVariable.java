package mdt.persistence.asset.mqtt;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import com.google.common.util.concurrent.Service.Listener;

import utils.Throwables;

import mdt.FaaastRuntime;
import mdt.client.support.MqttService;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AbstractAssetVariable;
import mdt.persistence.asset.AssetVariableException;
import mdt.persistence.mqtt.MqttBrokerConfig;

import de.fraunhofer.iosb.ilt.faaast.service.starter.InitializationException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttAssetVariable extends AbstractAssetVariable<MqttAssetVariableConfig> {
	private MqttService m_mqtt;
	
	public MqttAssetVariable(MqttAssetVariableConfig config) {
		super(config);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);

		MqttBrokerConfig brokerConfig = m_config.getMqttBrokerConfig();
		// endpoint 설정에 broker 정보가 없으면 global 설정에서 읽어옴
		if ( brokerConfig == null ) {
			throw new InitializationException("No MQTT broker configuration: " + this);
		}
		m_mqtt = new MqttService(brokerConfig.getBrokerUrl(), "MqttEndpoint");
		m_mqtt.addListener(new Listener() {
		    public void running() {
		        getLogger().info("Initialized {}", MqttAssetVariable.this);
		    }
		}, FaaastRuntime.getExecutor());
		
		m_mqtt.startAsync();
	}

	@Override
	public boolean isReadable() {
		return false;
	}

	@Override
	public boolean isUpdatable() {
        return true;
    }

	@Override
	public SubmodelElement read() throws AssetVariableException {
		throw new AssetVariableException("read is not supported for MQTT AssetVariable: " + this);
	}

	@Override
	public void update(SubmodelElement newElement) throws AssetVariableException {
		try {
			String valueJson = ElementValues.getValue(newElement).toValueJsonString();
			m_mqtt.publish(m_config.getTopic(), valueJson);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to publish MQTT message: %s, cause=%s",
										this, e.getMessage());
			throw new AssetVariableException(msg, cause);
		}
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), getConfig());
	}
}
