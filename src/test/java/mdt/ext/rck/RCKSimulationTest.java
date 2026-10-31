package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import utils.async.AsyncResult;
import utils.websocket.Signals.WebSocketError;
import utils.websocket.WebSocketClosedException;


/**
 * {@link RCKSimulation}의 중지·취소 절차를 가짜 RCK 서버로 검증한다.
 * <p>
 * 중지 명령은 연결을 닫기 전에 시뮬레이터에 전달되어야 하고, {@code cancel()}은 {@code Future} 규약대로
 * 차트를 취소해야 한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationTest {
	private MockWebServer m_server;
	private RCKSimulation m_sim;

	@After
	public void tearDown() throws Exception {
		if ( m_sim != null && !m_sim.isDone() ) {
			m_sim.cancel(true);
		}
		if ( m_server != null ) {
			m_server.close();
		}
	}

	private RCKSimulation startSimulation(okhttp3.WebSocketListener serverListener, MockResponse response)
		throws Exception {
		RCKSimulation sim = createSimulation(serverListener, response);
		sim.start();
		return sim;
	}

	private RCKSimulation createSimulation(okhttp3.WebSocketListener serverListener, MockResponse response)
		throws Exception {
		return createSimulation(serverListener, response, SimulationObserver.NONE);
	}

	/** 받은 알림을 순서대로 기록하는 관찰자. */
	static class RecordingObserver implements SimulationObserver {
		final List<String> m_events = Collections.synchronizedList(new ArrayList<>());

		@Override
		public void onStateChanged(String statePath, String displayName) {
			m_events.add(statePath);
		}
		@Override
		public void onProgressUpdated(RCKSimulationProgress progress) {
			m_events.add("status:" + progress.progress() + "/" + progress.production() + "/"
						+ progress.averageUtilizations());
		}
		@Override
		public void onVideoReady(String downloadUrl) {
			m_events.add("video:" + downloadUrl);
		}
		@Override
		public void onFinished(RCKSimulationResult result) {
			m_events.add("finished:" + result.termination().code() + ":" + result.termination().displayName()
						+ ":" + result.failureCause());
		}

		String last() {
			return m_events.get(m_events.size() - 1);
		}
	}

	private RCKSimulation createSimulation(okhttp3.WebSocketListener serverListener, MockResponse response,
											SimulationObserver observer) throws Exception {
		m_server = new MockWebServer();
		m_server.enqueue(response.withWebSocketUpgrade(serverListener));
		m_server.start();

		String url = "ws://" + m_server.getHostName() + ":" + m_server.getPort() + "/rck";
		m_sim = new RCKSimulation(new RCKSimulationContext(url, "test-client", "press", "layout"), observer);
		return m_sim;
	}

	private void awaitState(RCKSimulation sim, String path) throws InterruptedException {
		long due = System.currentTimeMillis() + 5000;
		while ( System.currentTimeMillis() < due ) {
			if ( sim.isRunning() && path.equals(sim.getCurrentState().getPath()) ) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("state not reached: " + path + ", current=" + sim.getCurrentState());
	}

	@Test(timeout = 15_000)
	public void requestStop_sends_stop_and_finishes_as_stopped() throws Exception {
		FakeRckServer server = new FakeRckServer(true, false);
		RCKSimulation sim = startSimulation(server, new MockResponse());
		awaitState(sim, "/Running");

		sim.requestStop();

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Stopped", sim.getCurrentState().getPath());
		RCKSimulationResult result = sim.getSimulationResult();
		assertEquals(RCKSimulationResult.Termination.STOPPED, result.termination());
		assertTrue("simulation_stop was received", result.stopConfirmed());
		server.assertStopBeforeClose();
	}

	@Test(timeout = 15_000)
	public void cancel_sends_stop_before_closing_and_cancels_chart() throws Exception {
		FakeRckServer server = new FakeRckServer(false, false);
		RCKSimulation sim = startSimulation(server, new MockResponse());
		awaitState(sim, "/Running");

		assertTrue(sim.cancel(true));

		assertTrue(sim.isCancelled());
		server.assertStopBeforeClose();
	}

	@Test(timeout = 15_000)
	public void failure_sends_stop_before_closing() throws Exception {
		// 시작 후 잘못된 메시지가 오면 Failed로 끝난다. 이때도 중지 명령이 연결 종료보다 먼저 가야 한다.
		FakeRckServer server = new FakeRckServer(false, true);
		RCKSimulation sim = startSimulation(server, new MockResponse());

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isFailed());
		assertEquals("/Failed", sim.getCurrentState().getPath());
		server.assertStopBeforeClose();
	}

	@Test(timeout = 15_000)
	public void cancel_while_connecting_cancels_chart() throws Exception {
		// 이전에는 접속 중 cancel()이 STOP 신호만 보내 아무 일도 일어나지 않았는데 true를 반환했다.
		FakeRckServer server = new FakeRckServer(true, false);
		RCKSimulation sim = startSimulation(server,
								new MockResponse().setHeadersDelay(500, TimeUnit.MILLISECONDS));

		assertTrue(sim.cancel(true));

		assertTrue(sim.isCancelled());
		Thread.sleep(800);
		assertFalse("no command must be sent after cancel: " + server.m_events, server.m_events.contains("register"));
	}

	@Test(timeout = 15_000)
	public void requestStop_before_start_cancels_chart() throws Exception {
		m_sim = new RCKSimulation(new RCKSimulationContext("ws://127.0.0.1:1/rck", "test-client", "press", "layout"));

		m_sim.requestStop();

		assertTrue(m_sim.isCancelled());
	}

	@Test(timeout = 15_000)
	public void requestStop_queued_inside_callback_cancels_while_connecting() throws Exception {
		// 신호 처리 스레드 안(whenStarted 동기 리스너)에서 부르면 STOP은 큐에 들어갔다가 접속 상태에서 처리된다.
		// 처리되지 않은 STOP도 취소로 이어져야 한다(이전에는 RUNNING, /OpenWebSocket에 남았다).
		m_server = new MockWebServer();
		m_server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS)
								.withWebSocketUpgrade(new FakeRckServer(true, false)));
		m_server.start();
		String url = "ws://" + m_server.getHostName() + ":" + m_server.getPort() + "/rck";
		m_sim = new RCKSimulation(new RCKSimulationContext(url, "test-client", "press", "layout"));
		m_sim.whenStarted(m_sim::requestStop);

		m_sim.start();

		assertTrue(m_sim.waitForFinished(2, TimeUnit.SECONDS).isCancelled());
	}

	@Test(timeout = 15_000)
	public void requestStop_without_response_finishes_as_stopped_after_timeout() throws Exception {
		// 시뮬레이터가 simulation_stop으로 응답하지 않아도 대기 시간 뒤 Stopped로 끝난다(중지 확인은 없음).
		FakeRckServer server = new FakeRckServer(false, false);
		RCKSimulation sim = startSimulation(server, new MockResponse());
		sim.setStopResponseTimeout(java.time.Duration.ofMillis(300));
		awaitState(sim, "/Running");

		sim.requestStop();

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Stopped", sim.getCurrentState().getPath());
		assertFalse(sim.getSimulationResult().stopConfirmed());
		server.assertStopBeforeClose();
	}

	static final String STATUS
		= "{\"type\":\"simulation_status\",\"message\":{\"Progress\":100,"
			+ "\"Conveyor_6\":{\"Production\":7},\"e1\":{\"Name\":\"TP-75 #1\",\"Utilization\":40}}}";
	static String videoInfo(long fileSize) {
		// 모르는 필드(extra)가 있어도 실패하지 않아야 한다.
		return "{\"type\":\"video_info\",\"status\":\"ready\",\"fileName\":\"v.mp4\",\"fileSize\":" + fileSize
				+ ",\"downloadUrl\":\"http://video/v.mp4\",\"extra\":1}";
	}

	@Test(timeout = 15_000)
	public void video_info_reports_url_and_completes_after_receiving_video() throws Exception {
		// video_info는 한 번만 온다. 받으면 바로 영상 수신으로 가고, 영상을 다 받으면 Completed로 끝난다.
		FakeRckServer server = new FakeRckServer(false, false)
									.thenSend(STATUS, videoInfo(6), new byte[] {1, 2, 3}, new byte[] {4, 5, 6});
		RecordingObserver observer = new RecordingObserver();
		List<String> events = observer.m_events;
		RCKSimulation sim = createSimulation(server, new MockResponse(), observer);
		RCKSimulationContext ctx = sim.getContext();
		sim.start();

		assertTrue(m_sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Completed", m_sim.getCurrentState().getPath());
		assertEquals("http://video/v.mp4", ctx.getVideoInfo().downloadUrl());
		assertEquals(100, ctx.getLatestProgress().progress());
		RCKSimulationResult result = sim.getSimulationResult();
		assertEquals(RCKSimulationResult.Termination.COMPLETED, result.termination());
		assertEquals("http://video/v.mp4", result.videoUrl());
		assertEquals(100, result.progress().progress());
		assertTrue(events.toString(), events.contains("status:100/7/{TP-75=40.0}"));
		assertTrue(events.toString(), events.contains("video:http://video/v.mp4"));
		// 접속 단계부터 알리고(이전 실행의 상태가 남지 않게), 종료는 onFinished로 한 번 알린다.
		assertEquals(events.toString(), "/ConnectingWebSocket", events.get(0));
		assertTrue(events.toString(), events.indexOf("/ReceivingVideo") >= 0);
		assertEquals(events.toString(), "finished:Completed:시뮬레이션 완료:null", observer.last());
		server.assertStopBeforeClose();
	}

	@Test(timeout = 15_000)
	public void malformed_progress_messages_are_ignored() throws Exception {
		// 형식이 잘못된 진행 보고와 예상 밖 형식의 video_progress는 경고만 남기고 시뮬레이션을 계속한다.
		FakeRckServer server = new FakeRckServer(false, false)
				.thenSend("{\"type\":\"simulation_status\",\"message\":{}}",
						"{\"type\":\"video_progress\",\"progress\":{\"percent\":10}}",
						"{\"type\":\"video_ready\",\"fileSize\":\"big\"}",
						STATUS, videoInfo(0));
		RCKSimulation sim = startSimulation(server, new MockResponse());

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals(100, sim.getContext().getLatestProgress().progress());
	}

	@Test(timeout = 15_000)
	public void connection_error_fails_through_failed_state_and_reports_finish() throws Exception {
		// pong 시간 초과 같은 연결 오류도 Failed 상태를 거쳐야 한다: 중지 명령을 보내고 관찰자에게 끝을 알린다.
		FakeRckServer server = new FakeRckServer(false, false);
		RecordingObserver observer = new RecordingObserver();
		RCKSimulation sim = createSimulation(server, new MockResponse(), observer);
		sim.start();
		awaitState(sim, "/Running");

		java.io.IOException error = new java.io.IOException("pong timeout");
		sim.handleSignal(new WebSocketError(sim.getWebSocket(), error));

		AsyncResult<RCKSimulationContext> result = sim.waitForFinished(5, TimeUnit.SECONDS);
		assertTrue(result.isFailed());
		assertEquals(error, result.getFailureCause());
		assertEquals("/Failed", sim.getCurrentState().getPath());
		assertEquals("finished:Failed:시뮬레이션 실패:" + error, observer.last());
		server.assertStopBeforeClose();
	}

	@Test(timeout = 15_000)
	public void connection_error_while_receiving_video_completes() throws Exception {
		// 영상 수신 중의 연결 오류는 URL을 이미 받았으므로 Completed로 끝난다.
		FakeRckServer server = new FakeRckServer(false, false).thenSend(videoInfo(1000));
		RCKSimulation sim = startSimulation(server, new MockResponse());
		awaitState(sim, "/ReceivingVideo");

		sim.handleSignal(new WebSocketError(sim.getWebSocket(), new java.io.IOException("pong timeout")));

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Completed", sim.getCurrentState().getPath());
		assertEquals("http://video/v.mp4", sim.getContext().getVideoInfo().downloadUrl());
	}

	@Test(timeout = 15_000)
	public void request_stop_while_receiving_video_goes_through_stopping() throws Exception {
		FakeRckServer server = new FakeRckServer(true, false).thenSend(videoInfo(1000));
		RCKSimulation sim = startSimulation(server, new MockResponse());
		awaitState(sim, "/ReceivingVideo");

		sim.requestStop();

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Stopped", sim.getCurrentState().getPath());
		server.assertStopBeforeClose();
	}

	@Test(timeout = 15_000)
	public void connection_error_while_stopping_finishes_as_stopped() throws Exception {
		// 중지 명령에 응답하지 않는 시뮬레이터. Stopping에서 연결 오류가 나면 Stopped로 끝난다.
		FakeRckServer server = new FakeRckServer(false, false);
		RCKSimulation sim = createSimulation(server, new MockResponse());
		sim.setStopResponseTimeout(java.time.Duration.ofSeconds(30));
		sim.start();
		awaitState(sim, "/Running");
		sim.requestStop();
		awaitState(sim, "/Stopping");

		sim.handleSignal(new WebSocketError(sim.getWebSocket(), new java.io.IOException("pong timeout")));

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Stopped", sim.getCurrentState().getPath());
	}

	@Test(timeout = 15_000)
	public void connection_closed_while_stopping_finishes_as_stopped() throws Exception {
		// 시뮬레이터가 중지 명령에 응답하지 않고 연결을 닫으면 Stopped로 끝난다.
		FakeRckServer server = new FakeRckServer(false, false) {
			@Override
			public void onMessage(okhttp3.WebSocket ws, String text) {
				super.onMessage(ws, text);
				if ( text.contains("\"stop\"") ) {
					ws.close(1000, "stopped");
				}
			}
		};
		RCKSimulation sim = createSimulation(server, new MockResponse());
		sim.setStopResponseTimeout(java.time.Duration.ofSeconds(30));
		sim.start();
		awaitState(sim, "/Running");

		sim.requestStop();

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Stopped", sim.getCurrentState().getPath());
	}

	@Test(timeout = 30_000)
	public void back_to_back_sends_are_serialized() throws Exception {
		// JDK WebSocket은 텍스트 송신을 한 번에 하나만 받는다. 큰 메시지를 연달아 보내면 소켓 버퍼가 차서 앞 송신이
		// 밀린다. 그래도 실패하지 않고 순서대로 가야 한다(이전에는 시작 명령 직후의 중지 명령 등이
		// IllegalStateException("Send pending")으로 실패할 수 있었다). 메시지 하나는 OkHttp 서버의 한도(16MB)보다 작게 한다.
		FakeRckServer server = new FakeRckServer(false, false);
		RCKSimulation sim = startSimulation(server, new MockResponse());
		awaitState(sim, "/Running");

		String payload = "x".repeat(8 * 1024 * 1024);
		List<java.util.concurrent.CompletableFuture<?>> sends = new ArrayList<>();
		for ( int i = 1; i <= 4; ++i ) {
			sends.add(sim.sendJson(Map.of("type", "probe" + i, "payload", payload)));
		}
		sends.add(sim.sendJson(Map.of("type", "probe5")));
		for ( var send: sends ) {
			send.get(10, TimeUnit.SECONDS);
		}

		long due = System.currentTimeMillis() + 10_000;
		while ( !server.m_events.contains("probe5") && System.currentTimeMillis() < due ) {
			Thread.sleep(20);
		}
		List<String> events = new ArrayList<>(server.m_events);
		for ( int i = 1; i < 5; ++i ) {
			assertTrue(events.toString(), events.indexOf("probe" + i) >= 0
										&& events.indexOf("probe" + i) < events.indexOf("probe" + (i + 1)));
		}
	}

	@Test(timeout = 15_000)
	public void cancel_reports_cancelled_finish() throws Exception {
		// 종료 상태를 거치지 않는 취소도 관찰자에게 알린다(이전에는 State가 진행 중 상태에 멈췄다).
		FakeRckServer server = new FakeRckServer(false, false);
		RecordingObserver observer = new RecordingObserver();
		RCKSimulation sim = createSimulation(server, new MockResponse(), observer);
		sim.start();
		awaitState(sim, "/Running");

		assertTrue(sim.cancel(true));

		assertEquals("finished:Cancelled:시뮬레이션 취소됨:null", observer.last());
	}

	@Test(timeout = 15_000)
	public void connection_closed_while_receiving_video_completes_with_url() throws Exception {
		FakeRckServer server = new FakeRckServer(false, false) {
			@Override
			public void onMessage(okhttp3.WebSocket ws, String text) {
				super.onMessage(ws, text);
				if ( text.contains("\"command\"") ) {
					ws.close(1000, "done");
				}
			}
		}.thenSend(videoInfo(1000));
		RCKSimulation sim = startSimulation(server, new MockResponse());

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Completed", sim.getCurrentState().getPath());
		assertEquals("http://video/v.mp4", sim.getContext().getVideoInfo().downloadUrl());
	}

	@Test(timeout = 15_000)
	public void no_response_fails_after_response_timeout() throws Exception {
		// 시뮬레이터가 연결만 유지하고 응답하지 않으면 응답 대기 시간 뒤 Failed로 끝난다.
		FakeRckServer server = new FakeRckServer(false, false);
		server.m_ignoreLayout = true;
		RCKSimulation sim = createSimulation(server, new MockResponse());
		sim.setResponseTimeout(java.time.Duration.ofMillis(300));
		sim.start();

		AsyncResult<RCKSimulationContext> result = sim.waitForFinished(5, TimeUnit.SECONDS);
		assertTrue(result.isFailed());
		assertEquals("/Failed", sim.getCurrentState().getPath());
		assertTrue("" + result.getFailureCause(), result.getFailureCause() instanceof TimeoutException);
		server.assertStopBeforeClose();
	}

	@Test(timeout = 15_000)
	public void connection_closed_by_simulator_while_running_fails() throws Exception {
		// 시뮬레이션 도중 시뮬레이터가 정상 close해도 사용자가 취소한 것이 아니므로 Failed로 끝난다(이전에는 CANCELLED).
		FakeRckServer server = new FakeRckServer(false, false) {
			@Override
			public void onMessage(okhttp3.WebSocket ws, String text) {
				super.onMessage(ws, text);
				if ( text.contains("\"command\"") ) {
					ws.close(1000, "bye");
				}
			}
		}.thenSend(STATUS);
		RCKSimulation sim = startSimulation(server, new MockResponse());

		AsyncResult<RCKSimulationContext> result = sim.waitForFinished(5, TimeUnit.SECONDS);
		assertTrue(result.isFailed());
		assertEquals("/Failed", sim.getCurrentState().getPath());
		assertTrue("" + result.getFailureCause(), result.getFailureCause() instanceof WebSocketClosedException);
	}
}
