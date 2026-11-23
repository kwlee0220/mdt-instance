package mdt.endpoint.opcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CertificateConfig;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.util.EncodingHelper;

import utils.func.CheckedRunnable;

import mdt.assetconnection.operation.OperationExecution;
import mdt.assetconnection.operation.OperationExecution.State;
import mdt.assetconnection.operation.OperationExecutionRegistry;
import mdt.model.MDTModelSerDe;


/**
 * {@link OperationControlEndpoint}의 응답 규약(T3)과 보존·종료(T4) 테스트.
 */
public class OperationControlEndpointTest {
	private static final Duration WAIT = Duration.ofSeconds(5);

	@Rule public TemporaryFolder m_tempDir = new TemporaryFolder();

	private OperationExecutionRegistry m_registry;
	private OperationControlEndpoint m_endpoint;
	private final List<String> m_calls = new CopyOnWriteArrayList<>();
	private HttpClient m_client = HttpClient.newHttpClient();
	private boolean m_https = false;

	@Before
	public void setUp() {
		m_registry = new OperationExecutionRegistry();
	}

	@After
	public void tearDown() {
		if ( m_endpoint != null ) {
			m_endpoint.stop();
		}
		m_registry.shutdown(Duration.ofSeconds(1));
	}

	private void startEndpoint(Duration cancelWait, Duration retainTimeout, CertificateConfig cert) throws Exception {
		m_endpoint = new OperationControlEndpoint(m_registry);
		m_endpoint.init(new CoreConfig(), new OperationControlEndpointConfig(0, cancelWait, retainTimeout, cert),
						Mockito.mock(ServiceContext.class));
		m_endpoint.start();
	}

	private OperationExecution register(String handleId, CheckedRunnable work) {
		OperationExecution exec = new OperationExecution(handleId, "op", work,
														() -> m_calls.add("completed"),
														e -> m_calls.add("failed"),
														() -> m_calls.add("cancelled"));
		m_registry.register(exec);
		exec.start(m_registry.getExecutor());
		return exec;
	}

	private String url(String handleId) {
		return String.format("%s://localhost:%d%s%s", m_https ? "https" : "http", m_endpoint.getPort(),
								OperationControlEndpoint.EXECUTIONS_PATH, EncodingHelper.base64UrlEncode(handleId));
	}

	private HttpResponse<String> send(String method, String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody())
									.timeout(Duration.ofSeconds(10)).build();
		return m_client.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private static JsonNode body(HttpResponse<String> resp) throws IOException {
		return MDTModelSerDe.MAPPER.readTree(resp.body());
	}

	@Test(timeout = 15_000)
	public void cancelFinishingWithinWaitReturns200() throws Exception {
		startEndpoint(WAIT, Duration.ofSeconds(30), null);
		CountDownLatch entered = new CountDownLatch(1);
		OperationExecution exec = register("h-1", () -> { entered.countDown(); Thread.sleep(60_000); });
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		HttpResponse<String> resp = send("DELETE", url("h-1"));
		assertEquals(200, resp.statusCode());
		assertEquals("CANCELLED", body(resp).get("status").asText());
		assertEquals(OperationControlEndpoint.EXECUTIONS_PATH + EncodingHelper.base64UrlEncode("h-1"),
					body(resp).get("session_endpoint").asText());
		// 200이면 취소 처리(CANCELED 저장)가 이미 끝났다.
		assertEquals(State.CANCELLED, exec.getState());
		assertEquals(List.of("cancelled"), m_calls);
	}

	@Test(timeout = 15_000)
	public void cancelStillStoppingReturns202() throws Exception {
		startEndpoint(Duration.ofMillis(200), Duration.ofSeconds(30), null);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		OperationExecution exec = register("h-2", () -> {
			entered.countDown();
			awaitUninterruptibly(release);
		});
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		HttpResponse<String> resp = send("DELETE", url("h-2"));
		assertEquals(202, resp.statusCode());
		assertEquals("RUNNING", body(resp).get("status").asText());
		assertEquals(State.CANCELLING, exec.getState());

		// 다시 보내도 받아들인다.
		assertEquals(202, send("DELETE", url("h-2")).statusCode());

		release.countDown();
		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(State.CANCELLED, exec.getState());
		assertEquals(List.of("cancelled"), m_calls);
	}

	@Test(timeout = 15_000)
	public void cancelFinishedReturns409() throws Exception {
		startEndpoint(WAIT, Duration.ofSeconds(30), null);
		OperationExecution exec = register("h-3", () -> { });
		assertTrue(exec.awaitFinished(WAIT));

		HttpResponse<String> resp = send("DELETE", url("h-3"));
		assertEquals(409, resp.statusCode());
		assertTrue(body(resp).get("message").asText().contains("COMPLETED"));
		assertEquals(List.of("completed"), m_calls);
	}

	@Test(timeout = 15_000)
	public void unknownHandleReturns404() throws Exception {
		startEndpoint(WAIT, Duration.ofSeconds(30), null);
		assertEquals(404, send("DELETE", url("unknown")).statusCode());
		assertEquals(404, send("GET", url("unknown")).statusCode());
	}

	@Test(timeout = 15_000)
	public void invalidHandleEncodingReturns400() throws Exception {
		startEndpoint(WAIT, Duration.ofSeconds(30), null);
		String url = String.format("http://localhost:%d%s%s", m_endpoint.getPort(),
									OperationControlEndpoint.EXECUTIONS_PATH, "not*base64");
		assertEquals(400, send("DELETE", url).statusCode());
	}

	@Test(timeout = 15_000)
	public void unsupportedMethodReturns405() throws Exception {
		startEndpoint(WAIT, Duration.ofSeconds(30), null);
		HttpResponse<String> resp = send("PUT", url("h-x"));
		assertEquals(405, resp.statusCode());
		assertEquals("DELETE, GET", resp.headers().firstValue("Allow").orElse(""));
	}

	@Test(timeout = 15_000)
	public void getReturnsExecutionState() throws Exception {
		startEndpoint(WAIT, Duration.ofSeconds(30), null);
		CountDownLatch entered = new CountDownLatch(1);
		register("h-4", () -> { entered.countDown(); Thread.sleep(60_000); });
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		HttpResponse<String> resp = send("GET", url("h-4"));
		assertEquals(200, resp.statusCode());
		assertEquals("h-4", body(resp).get("handle").asText());
		assertEquals("RUNNING", body(resp).get("state").asText());
	}

	@Test(timeout = 15_000)
	public void finishedExecutionIsGoneAfterRetainTimeout() throws Exception {
		startEndpoint(WAIT, Duration.ZERO, null);
		assertEquals("init applies retainTimeout", Duration.ZERO, m_registry.getRetainTimeout());

		OperationExecution exec = register("h-5", () -> { });
		assertTrue(exec.awaitFinished(WAIT));
		Thread.sleep(10);
		assertEquals(404, send("DELETE", url("h-5")).statusCode());
	}

	@Test(timeout = 15_000)
	public void cancelSaveFailureIsReportedAndRetriedByAnotherDelete() throws Exception {
		startEndpoint(WAIT, Duration.ofSeconds(30), null);
		java.util.concurrent.atomic.AtomicBoolean saveFails = new java.util.concurrent.atomic.AtomicBoolean(true);
		CountDownLatch entered = new CountDownLatch(1);
		OperationExecution exec = new OperationExecution("h-7", "op", () -> { entered.countDown(); Thread.sleep(60_000); },
														() -> { }, e -> { },
														() -> {
															if ( saveFails.get() ) {
																throw new IllegalStateException("persistence down");
															}
															m_calls.add("cancelled");
														});
		m_registry.register(exec);
		exec.start(m_registry.getExecutor());
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		// 실행은 취소로 끝났지만 CANCELED를 저장하지 못했다: 500으로 알린다.
		HttpResponse<String> resp = send("DELETE", url("h-7"));
		assertEquals(500, resp.statusCode());
		assertEquals(State.CANCELLED, exec.getState());
		assertTrue(body(send("GET", url("h-7"))).get("cancelSaveFailure").asText().contains("persistence down"));

		// 다시 보내면 저장을 다시 시도한다.
		assertEquals(500, send("DELETE", url("h-7")).statusCode());
		saveFails.set(false);
		HttpResponse<String> again = send("DELETE", url("h-7"));
		assertEquals(200, again.statusCode());
		assertEquals("CANCELLED", body(again).get("status").asText());
		assertEquals(List.of("cancelled"), m_calls);
		assertFalse(body(send("GET", url("h-7"))).has("cancelSaveFailure"));

		// 저장된 뒤에는 이미 끝난 실행이다.
		assertEquals(200, send("DELETE", url("h-7")).statusCode());
	}

	@Test(timeout = 15_000)
	public void repeatedDeleteWhileSavingIsNotReportedAsCancelled() throws Exception {
		// CANCELED 저장이 끝나기 전에 온 반복 DELETE는 200이 아니라 202(멈추는 중)이다.
		startEndpoint(Duration.ofMillis(300), Duration.ofSeconds(30), null);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch saveRelease = new CountDownLatch(1);
		OperationExecution exec = new OperationExecution("h-8", "op", () -> { entered.countDown(); Thread.sleep(60_000); },
														() -> { }, e -> { },
														() -> {
															saveRelease.await();
															m_calls.add("cancelled");
														});
		m_registry.register(exec);
		exec.start(m_registry.getExecutor());
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		assertEquals(202, send("DELETE", url("h-8")).statusCode());
		assertEquals(202, send("DELETE", url("h-8")).statusCode());
		assertEquals(List.of(), m_calls);

		saveRelease.countDown();
		assertTrue(exec.awaitFinished(WAIT));
		assertEquals(200, send("DELETE", url("h-8")).statusCode());
		assertEquals(List.of("cancelled"), m_calls);
	}

	@Test(timeout = 15_000)
	public void slowSaveRetryAnswers202WithinCancelWait() throws Exception {
		// 첫 저장은 실패하고, 반복 DELETE가 시작한 재시도 저장은 오래 걸린다.
		startEndpoint(Duration.ofMillis(300), Duration.ofSeconds(30), null);
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch entered = new CountDownLatch(1);
		OperationExecution exec = new OperationExecution("h-9", "op", () -> { entered.countDown(); Thread.sleep(60_000); },
														() -> { }, e -> { },
														() -> {
															if ( attempts.incrementAndGet() == 1 ) {
																throw new IllegalStateException("persistence down");
															}
															release.await();
														});
		m_registry.register(exec);
		exec.start(m_registry.getExecutor());
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		assertEquals(500, send("DELETE", url("h-9")).statusCode());

		// 재시도 저장이 끝나지 않아도 cancelWait 안에 202로 답한다.
		long t0 = System.nanoTime();
		assertEquals(202, send("DELETE", url("h-9")).statusCode());
		assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);

		// 저장 중에도 상태 조회는 바로 답한다.
		t0 = System.nanoTime();
		JsonNode status = body(send("GET", url("h-9")));
		assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);
		assertEquals("RUNNING", status.get("cancelSaveState").asText());

		// 진행 중인 재시도에 다시 요청해도 새로 시작하지 않는다.
		assertEquals(202, send("DELETE", url("h-9")).statusCode());
		assertEquals(2, attempts.get());

		release.countDown();
		assertEquals(200, send("DELETE", url("h-9")).statusCode());
		assertEquals("SUCCEEDED", body(send("GET", url("h-9"))).get("cancelSaveState").asText());
	}

	@Test(timeout = 15_000)
	public void stopClosesThePort() throws Exception {
		startEndpoint(WAIT, Duration.ofSeconds(30), null);
		String url = url("h-6");
		m_endpoint.stop();
		assertEquals(-1, m_endpoint.getPort());
		m_endpoint = null;
		try {
			send("GET", url);
			fail("the port should be closed");
		}
		catch ( ConnectException expected ) { }
	}

	@Test(timeout = 30_000)
	public void httpsWithInstanceKeyStore() throws Exception {
		File keyStore = new File(m_tempDir.getRoot(), "test.p12");
		String keytool = new File(System.getProperty("java.home"), "bin/keytool").getAbsolutePath();
		Process proc = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server-key", "-keyalg", "RSA",
										"-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
										"-validity", "1", "-storetype", "PKCS12", "-keystore", keyStore.getPath(),
										"-storepass", "test-pass", "-keypass", "test-pass")
								.redirectErrorStream(true).start();
		assertTrue(proc.waitFor(20, TimeUnit.SECONDS));
		assertEquals(0, proc.exitValue());

		CertificateConfig cert = CertificateConfig.builder().keyStoreType("PKCS12").keyStorePath(keyStore)
													.keyStorePassword("test-pass").build();
		startEndpoint(WAIT, Duration.ofSeconds(30), cert);

		SSLContext trustAll = SSLContext.getInstance("TLS");
		trustAll.init(null, new TrustManager[] { new TrustAllManager() }, new SecureRandom());
		m_client = HttpClient.newBuilder().sslContext(trustAll).build();
		m_https = true;

		assertEquals(404, send("DELETE", url("unknown")).statusCode());
		assertFalse(url("unknown").startsWith("http:"));
	}

	private static final class TrustAllManager implements X509TrustManager {
		@Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
		@Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
		@Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while ( true ) {
			try {
				latch.await();
				break;
			}
			catch ( InterruptedException e ) {
				interrupted = true;
			}
		}
		if ( interrupted ) {
			Thread.currentThread().interrupt();
		}
	}
}
