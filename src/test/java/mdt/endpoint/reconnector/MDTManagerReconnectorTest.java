package mdt.endpoint.reconnector;

import static org.junit.Assert.assertEquals;

import org.junit.Test;


/**
 * 등록 요청 URL에 취소 엔드포인트 주소를 붙이는 규칙 테스트.
 */
public class MDTManagerReconnectorTest {
	private static final String REGISTER_URL = "http://mgr:12985/instance-manager/registry/robot-01";

	@Test
	public void noOperationControlEndpoint() {
		assertEquals(REGISTER_URL, MDTManagerReconnector.toRegisterPostUrl(REGISTER_URL, null));
	}

	@Test
	public void operationControlEndpointIsQueryEncoded() {
		assertEquals(REGISTER_URL + "?operationControlEndpoint=https%3A%2F%2Fhost%3A19081",
					MDTManagerReconnector.toRegisterPostUrl(REGISTER_URL, "https://host:19081"));
	}
}
