package mdt.assetconnection.operation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultKey;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;


/**
 * {@link HttpOperationProvider}가 실행 중 인터럽트되면(AAS 연산 취소) 연산 서버의 세션에 취소를 보내는지 확인한다.
 */
public class HttpOperationProviderCancelTest {
	private static final String SESSION = "/sessions/s1";

	private MockWebServer m_server;
	private final CountDownLatch m_polled = new CountDownLatch(1);
	private final AtomicBoolean m_deleted = new AtomicBoolean();

	@Before
	public void setUp() throws Exception {
		m_server = new MockWebServer();
		m_server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				String method = request.getMethod();
				if ( "POST".equals(method) ) {
					return json(200, "RUNNING");
				}
				if ( "DELETE".equals(method) && SESSION.equals(request.getPath()) ) {
					m_deleted.set(true);
					return json(200, "CANCELLED");
				}
				if ( "GET".equals(method) && SESSION.equals(request.getPath()) ) {
					m_polled.countDown();
					return json(200, m_deleted.get() ? "CANCELLED" : "RUNNING");
				}
				return new MockResponse().setResponseCode(404);
			}
		});
		m_server.start();
	}

	@After
	public void tearDown() throws Exception {
		m_server.shutdown();
	}

	@Test(timeout = 15_000)
	public void interruptSendsSessionDelete() throws Exception {
		String baseUrl = "http://" + m_server.getHostName() + ":" + m_server.getPort();
		HttpOperationProviderConfig config = new HttpOperationProviderConfig(baseUrl, "/api/v1/operations/op",
																				"100ms", null);
		HttpOperationProvider provider = new HttpOperationProvider(Mockito.mock(ServiceContext.class),
																	operationRef(), config);

		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				provider.invokeSync(new OperationVariable[0], new OperationVariable[0], new OperationVariable[0]);
			}
			catch ( Throwable e ) {
				failure.set(e);
			}
		});
		worker.start();
		assertTrue("session is polled", m_polled.await(5, TimeUnit.SECONDS));

		worker.interrupt();
		worker.join(10_000);

		assertTrue("remote session received DELETE", m_deleted.get());
		assertNotNull(failure.get());
		assertEquals(InterruptedException.class, failure.get().getClass());
	}

	private static MockResponse json(int code, String status) {
		String body = String.format("{\"session_endpoint\":\"%s\",\"status\":\"%s\",\"outputs\":{}}", SESSION, status);
		return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
	}

	private static Reference operationRef() {
		return new DefaultReference.Builder().type(ReferenceTypes.MODEL_REFERENCE)
				.keys(List.of(new DefaultKey.Builder().type(KeyTypes.SUBMODEL).value("urn:sm").build(),
						new DefaultKey.Builder().type(KeyTypes.OPERATION).value("Op").build())).build();
	}
}
