package mdt.endpoint.opcontrol;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import utils.func.FOption;
import utils.http.RESTfulErrorEntity;
import utils.rpc.restful.RpcResponseMessage;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CertificateConfig;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.AbstractEndpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.util.EncodingHelper;

import mdt.assetconnection.operation.OperationExecution;
import mdt.assetconnection.operation.OperationExecutionRegistry;
import mdt.config.MDTService;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;


/**
 * AAS 비동기 연산 호출({@code invoke-async})로 시작한 실행을 실행 ID로 취소하는 HTTP 엔드포인트.
 * <p>
 * AAS API와 FA³ST에는 실행 중인 연산을 취소하는 경로가 없으므로, 인스턴스가 별도 포트로 이 경로를 연다.
 * 실행 ID로 {@link OperationExecutionRegistry}에서 실행을 찾아 취소한다.
 * <table border="1">
 *   <caption>경로</caption>
 *   <tr><th>메소드</th><th>경로</th><th>동작</th></tr>
 *   <tr><td>{@code DELETE}</td><td>{@code /operation-executions/{handle}}</td><td>취소 요청</td></tr>
 *   <tr><td>{@code GET}</td><td>{@code /operation-executions/{handle}}</td><td>실행 상태 조회(진단용)</td></tr>
 * </table>
 * {@code {handle}}은 FA³ST {@code operation-status} 경로와 같은 base64url 인코딩의 실행 ID다.
 * <p>
 * {@code DELETE} 응답은 {@code utils.rpc.restful}의 취소 규약과 같다.
 * <ul>
 *   <li>{@code 200} + {@code {"status":"CANCELLED"}}: {@code cancelWait} 안에 실행이 끝났다. FA³ST의
 *       {@code operation-status}는 이미 {@code Canceled}다.</li>
 *   <li>{@code 202} + {@code {"status":"RUNNING"}}: 취소를 받았지만 실행이 아직 멈추는 중이다. 끝나는 즉시
 *       {@code Canceled}가 저장된다.</li>
 *   <li>{@code 409}: 실행이 이미 끝났다(보존 시간 안).</li>
 *   <li>{@code 500}: 실행은 취소로 끝났지만 FA³ST에 {@code CANCELED}를 저장하지 못했다. 같은 요청을 다시 보내면
 *       저장을 다시 시도하고, 성공하면 {@code 200}으로 답한다. 재시도는 별도 스레드에서 돌고, 요청은 {@code cancelWait}
 *       안에 끝나지 않으면 {@code 202}로 답한다.</li>
 *   <li>{@code 404}: 등록되지 않은 실행이거나 보존 시간이 지났다.</li>
 *   <li>{@code 400}: {@code {handle}}이 올바른 base64url이 아니다.</li>
 * </ul>
 * 인증서 설정이 있으면 HTTPS로 연다. 인증은 하지 않는다(FA³ST HTTP 엔드포인트와 같은 수준).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationControlEndpoint extends AbstractEndpoint<OperationControlEndpointConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(OperationControlEndpoint.class);

	/** 실행 경로의 접두어. */
	public static final String EXECUTIONS_PATH = "/operation-executions/";
	private static final String DEFAULT_KEYSTORE_TYPE = "PKCS12";

	@Nullable private OperationExecutionRegistry m_registry;
	@Nullable private HttpServer m_server;
	@Nullable private ExecutorService m_httpExecutor;

	/**
	 * FA³ST가 설정으로 엔드포인트를 만들 때 쓰는 생성자. 실행 목록은 {@link #init}에서 {@link MDTService}로부터 얻는다.
	 */
	public OperationControlEndpoint() { }

	/**
	 * 주어진 실행 목록을 쓰는 엔드포인트를 만든다.
	 *
	 * @param registry	취소할 실행을 찾을 실행 목록.
	 */
	OperationControlEndpoint(OperationExecutionRegistry registry) {
		m_registry = registry;
	}

	/**
	 * 엔드포인트를 초기화한다. 실행 목록의 보존 시간을 설정의 {@code retainTimeout}으로 맞춘다.
	 *
	 * @throws IllegalArgumentException	실행 목록이 주어지지 않았고 {@code serviceContext}가 {@link MDTService}가
	 * 									아닌 경우.
	 */
	@Override
	public void init(CoreConfig coreConfig, OperationControlEndpointConfig config, ServiceContext serviceContext) {
		super.init(coreConfig, config, serviceContext);

		if ( m_registry == null ) {
			if ( serviceContext instanceof MDTService svc ) {
				m_registry = svc.getOperationExecutionRegistry();
			}
			else {
				throw new IllegalArgumentException("OperationControlEndpoint requires MDTService: context="
													+ serviceContext.getClass().getName());
			}
		}
		m_registry.setRetainTimeout(config.getRetainTimeout());
	}

	@Override
	public void start() throws EndpointException {
		OperationControlEndpointConfig config = asConfig();
		try {
			InetSocketAddress addr = new InetSocketAddress(config.getPort());
			CertificateConfig cert = config.getCertificate();
			HttpServer server;
			if ( cert != null ) {
				HttpsServer https = HttpsServer.create(addr, 0);
				https.setHttpsConfigurator(new HttpsConfigurator(newSslContext(cert)));
				server = https;
			}
			else {
				server = HttpServer.create(addr, 0);
			}
			m_httpExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory());
			server.setExecutor(m_httpExecutor);
			server.createContext(EXECUTIONS_PATH, this::handle);
			server.start();
			m_server = server;

			s_logger.info("Starting service: {}[port={}, https={}, cancelWait={}, retainTimeout={}]",
							getClass().getSimpleName(), getPort(), cert != null, config.getCancelWait(),
							config.getRetainTimeout());
		}
		catch ( IOException | GeneralSecurityException e ) {
			if ( m_httpExecutor != null ) {
				m_httpExecutor.shutdownNow();
				m_httpExecutor = null;
			}
			throw new EndpointException("failed to start OperationControlEndpoint: port=" + config.getPort(), e);
		}
	}

	@Override
	public void stop() {
		HttpServer server = m_server;
		if ( server != null ) {
			s_logger.info("Stopping service: {}[port={}]", getClass().getSimpleName(), getPort());
			server.stop(0);
			m_server = null;
		}
		if ( m_httpExecutor != null ) {
			m_httpExecutor.shutdownNow();
			m_httpExecutor = null;
		}
	}

	/**
	 * 실제로 열린 포트를 반환한다. 설정의 포트가 {@code 0}이면 고른 포트다.
	 *
	 * @return	포트. 시작 전이면 {@code -1}.
	 */
	public int getPort() {
		HttpServer server = m_server;
		return (server != null) ? server.getAddress().getPort() : -1;
	}

	private void handle(HttpExchange exchange) {
		try {
			String path = exchange.getRequestURI().getPath();
			String encoded = path.substring(EXECUTIONS_PATH.length());
			if ( encoded.isEmpty() || encoded.contains("/") ) {
				sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND, new ResourceNotFoundException("path: " + path));
				return;
			}

			String handleId;
			try {
				handleId = EncodingHelper.base64UrlDecode(encoded);
			}
			catch ( IllegalArgumentException e ) {
				sendError(exchange, HttpURLConnection.HTTP_BAD_REQUEST,
							new IllegalArgumentException("invalid base64url handle: " + encoded));
				return;
			}

			String sessionEndpoint = EXECUTIONS_PATH + encoded;
			switch ( exchange.getRequestMethod() ) {
				case "DELETE" -> cancel(exchange, handleId, sessionEndpoint);
				case "GET" -> status(exchange, handleId);
				default -> {
					exchange.getResponseHeaders().set("Allow", "DELETE, GET");
					sendError(exchange, HttpURLConnection.HTTP_BAD_METHOD,
								new UnsupportedOperationException("method: " + exchange.getRequestMethod()));
				}
			}
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
		catch ( Exception e ) {
			s_logger.warn("failed to handle an operation control request: uri={}, cause={}",
							exchange.getRequestURI(), e.toString());
		}
		finally {
			exchange.close();
		}
	}

	private void cancel(HttpExchange exchange, String handleId, String sessionEndpoint)
		throws IOException, InterruptedException {
		FOption<OperationExecution> found = m_registry.find(handleId);
		if ( found.isAbsent() ) {
			sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND,
						new ResourceNotFoundException("OperationExecution", "handle=" + handleId));
			return;
		}

		OperationExecution exec = found.get();
		// 실행 중이면 이번 요청으로, 취소 중이면 앞선 요청으로 취소가 받아들여진 것이다.
		boolean accepted = exec.cancel();
		if ( !accepted && exec.getState() != OperationExecution.State.CANCELLED ) {
			sendError(exchange, HttpURLConnection.HTTP_CONFLICT,
						new IllegalStateException("operation already finished: " + exec.getState()));
			return;
		}

		// 반복 요청을 포함해 모든 경우에 하나의 cancelWait 안에서 실행 종료와 저장 완료를 차례로 기다린다.
		// 실행은 첫 취소 처리기(CANCELED 저장)가 끝난 뒤에 종료를 알린다.
		long deadline = System.nanoTime() + asConfig().getCancelWait().toNanos();
		if ( !exec.awaitFinished(asConfig().getCancelWait()) ) {
			s_logger.info("operation cancel accepted, still stopping: op={}, handle={}",
							exec.getOperationName(), handleId);
			sendJson(exchange, HttpURLConnection.HTTP_ACCEPTED, RpcResponseMessage.running(sessionEndpoint));
			return;
		}

		// 이번 요청으로 끝난 취소는 그 결과를 알리고, 이미 끝난 취소에 다시 온 요청이면 실패했던 저장을 다시 시도한다.
		// 재시도는 실행의 executor에서 돌고, 이 요청은 남은 시간만 기다린다.
		OperationExecution.CancellationHandlerState handlerState;
		if ( accepted ) {
			handlerState = exec.getCancellationHandlerState();
		}
		else {
			Duration remaining = Duration.ofNanos(Math.max(deadline - System.nanoTime(), 0));
			handlerState = exec.retryCancellationHandler(remaining);
			if ( exec.isExpired() ) {
				// 찾은 뒤 재시도 전에 보존 시간이 지나 실행 목록에서 지워졌다. 재시도는 시작되지 않았다.
				sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND,
							new ResourceNotFoundException("OperationExecution", "handle=" + handleId));
				return;
			}
		}
		switch ( handlerState ) {
			case SUCCEEDED -> sendCancelled(exchange, exec, handleId, sessionEndpoint);
			case FAILED -> sendCancelSaveFailure(exchange, exec);
			// 저장이 아직 진행 중이다(다른 요청이 시작한 재시도 포함). 멈추는 중으로 답한다.
			case RUNNING, NOT_RUN -> {
				s_logger.info("operation cancelled, CANCELED status is still being saved: op={}, handle={}",
								exec.getOperationName(), handleId);
				sendJson(exchange, HttpURLConnection.HTTP_ACCEPTED, RpcResponseMessage.running(sessionEndpoint));
			}
		}
	}

	private static void sendCancelled(HttpExchange exchange, OperationExecution exec, String handleId,
										String sessionEndpoint) throws IOException {
		s_logger.info("operation cancelled: op={}, handle={}", exec.getOperationName(), handleId);
		sendJson(exchange, HttpURLConnection.HTTP_OK, RpcResponseMessage.cancelled(sessionEndpoint));
	}

	private static void sendCancelSaveFailure(HttpExchange exchange, OperationExecution exec) throws IOException {
		Throwable cause = exec.getCancellationHandlerFailure();
		sendJson(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR,
				RESTfulErrorEntity.of("operation was cancelled, but its CANCELED status could not be saved; "
										+ "retry the cancel request to save it again", cause));
	}

	private void status(HttpExchange exchange, String handleId) throws IOException {
		FOption<OperationExecution> found = m_registry.find(handleId);
		if ( found.isAbsent() ) {
			sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND,
						new ResourceNotFoundException("OperationExecution", "handle=" + handleId));
			return;
		}

		OperationExecution exec = found.get();
		ObjectNode body = MDTModelSerDe.MAPPER.createObjectNode();
		body.put("handle", exec.getHandleId());
		body.put("operation", exec.getOperationName());
		body.put("state", exec.getState().name());
		// 저장 상태는 잠금을 짧게만 쥐고 읽으므로 저장 중이어도 바로 답한다.
		OperationExecution.CancellationHandlerState saveState = exec.getCancellationHandlerState();
		if ( saveState != OperationExecution.CancellationHandlerState.NOT_RUN ) {
			body.put("cancelSaveState", saveState.name());
		}
		Throwable saveFailure = exec.getCancellationHandlerFailure();
		if ( saveFailure != null ) {
			body.put("cancelSaveFailure", ""+saveFailure);
		}
		sendJson(exchange, HttpURLConnection.HTTP_OK, body);
	}

	private static void sendError(HttpExchange exchange, int status, Throwable error) throws IOException {
		sendJson(exchange, status, RESTfulErrorEntity.of(error));
	}

	private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = MDTModelSerDe.MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try ( OutputStream os = exchange.getResponseBody() ) {
			os.write(bytes);
		}
	}

	private static SSLContext newSslContext(CertificateConfig cert) throws IOException, GeneralSecurityException {
		String type = (cert.getKeyStoreType() != null) ? cert.getKeyStoreType() : DEFAULT_KEYSTORE_TYPE;
		char[] storePassword = toChars(cert.getKeyStorePassword());
		char[] keyPassword = (cert.getKeyPassword() != null) ? toChars(cert.getKeyPassword()) : storePassword;

		KeyStore keyStore = KeyStore.getInstance(type);
		try ( InputStream is = new FileInputStream(cert.getKeyStorePath()) ) {
			keyStore.load(is, storePassword);
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(keyStore, keyPassword);

		SSLContext context = SSLContext.getInstance("TLS");
		context.init(kmf.getKeyManagers(), null, null);
		return context;
	}

	private static char[] toChars(@Nullable String password) {
		return (password != null) ? password.toCharArray() : new char[0];
	}

	private static final class DaemonThreadFactory implements ThreadFactory {
		private final AtomicInteger m_seq = new AtomicInteger();

		@Override
		public Thread newThread(Runnable task) {
			Thread thread = new Thread(task, "operation-control-" + m_seq.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	}
}
