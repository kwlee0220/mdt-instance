package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import mdt.MDTGlobalConfigurations;
import mdt.config.ServiceEndpointConfigs;
import mdt.model.MDTModelSerDe;


/**
 * {@link MqttEndpoint#init}이 broker 설정을 찾지 못했을 때 원인을 보존한 예외를 던지는지 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttEndpointInitTest {
	@Test
	public void testMissingBrokerKeepsCause() throws Exception {
		File global = File.createTempFile("mdt-global-", ".json");
		global.deleteOnExit();
		Files.writeString(global.toPath(), "{ \"mqttBrokers\": { \"factory\": { \"brokerUrl\": \"tcp://localhost:1883\" } } }");
		MDTGlobalConfigurations.setGlobalConfigFile(global);
		
		ServiceEndpointConfigs configs = MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class)
									.readValue("{ \"mqtt\": { \"mqttBrokerConfig\": \"no-such-broker\", \"subscribers\": [] } }");
		MqttEndpointConfig config = new MqttEndpointConfig(configs.getMqtt());
		
		ConfigurationInitializationException e = assertThrows(ConfigurationInitializationException.class,
															() -> new MqttEndpoint().init(null, config, null));
		assertNotNull("the original exception must be kept as the cause", e.getCause());
		assertTrue(e.getMessage(), e.getMessage().contains("mqttBrokers.no-such-broker"));
	}
}
