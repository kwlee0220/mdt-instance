package mdt.endpoint.opcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonMappingException;

import mdt.assetconnection.operation.OperationExecutionRegistry;
import mdt.config.ServiceEndpointConfigs;
import mdt.model.MDTModelSerDe;


/**
 * 인스턴스 설정의 {@code serviceEndpoints.operationControl} 항목 읽기 테스트.
 */
public class OperationControlConfigTest {
	private static ServiceEndpointConfigs read(String json) throws Exception {
		return MDTModelSerDe.MAPPER.readerFor(ServiceEndpointConfigs.class).readValue(json);
	}

	@Test
	public void readsAllFields() throws Exception {
		ServiceEndpointConfigs confs = read("""
				{ "operationControl": { "port": 19081, "cancelWait": "1500ms", "retainTimeout": "PT1M" } }
				""");
		OperationControlEndpointConfig.MDTConfig conf = confs.getOperationControl();
		assertEquals(19081, conf.getPort());
		assertEquals(Duration.ofMillis(1500), conf.getCancelWait());
		assertEquals(Duration.ofMinutes(1), conf.getRetainTimeout());

		OperationControlEndpointConfig epConf = OperationControlEndpointConfig.from(conf, null);
		assertEquals(19081, epConf.getPort());
		assertNull(epConf.getCertificate());
		// 직렬화하면 시간 값만 ISO-8601로 나온다(인증서 설정은 나오지 않는다).
		String json = MDTModelSerDe.MAPPER.writeValueAsString(epConf);
		assertTrue(json, json.contains("\"cancelWait\":\"PT1.5S\""));
		assertTrue(json, !json.contains("certificate"));
	}

	@Test
	public void defaults() throws Exception {
		OperationControlEndpointConfig.MDTConfig conf = read("{ \"operationControl\": { \"port\": 19081 } }")
															.getOperationControl();
		assertEquals(OperationControlEndpointConfig.MDTConfig.DEFAULT_CANCEL_WAIT, conf.getCancelWait());
		assertEquals(OperationExecutionRegistry.DEFAULT_RETAIN_TIMEOUT, conf.getRetainTimeout());
	}

	@Test
	public void absentMeansDisabled() throws Exception {
		assertNull(read("{ }").getOperationControl());
	}

	@Test
	public void portZeroIsRejected() throws Exception {
		try {
			read("{ \"operationControl\": { \"port\": 0 } }");
			fail("port 0 should be rejected");
		}
		catch ( JsonMappingException expected ) { }
	}

	@Test(expected = IllegalStateException.class)
	public void missingPortIsRejectedOnUse() throws Exception {
		read("{ \"operationControl\": { } }").getOperationControl().getPort();
	}
}
