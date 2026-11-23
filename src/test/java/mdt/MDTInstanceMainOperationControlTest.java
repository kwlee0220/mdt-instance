package mdt;

import static org.junit.Assert.assertEquals;

import org.junit.Test;


/**
 * 취소 엔드포인트 주소 계산 테스트.
 */
public class MDTInstanceMainOperationControlTest {
	@Test
	public void followsSchemeAndHostOfInstanceEndpoint() {
		assertEquals("https://10.0.0.5:19081",
					MDTInstanceMain.toOperationControlEndpoint("https://10.0.0.5:19051/api/v3.0", 19081));
		assertEquals("http://localhost:19081",
					MDTInstanceMain.toOperationControlEndpoint("http://localhost:19051", 19081));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsEndpointWithoutHost() {
		MDTInstanceMain.toOperationControlEndpoint("/api/v3.0", 19081);
	}
}
