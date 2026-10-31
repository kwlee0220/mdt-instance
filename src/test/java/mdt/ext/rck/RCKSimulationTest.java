package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;


/**
 * {@link RCKSimulation}의 중지·취소 절차를 가짜 RCK 서버로 검증한다.
 * <p>
 * 중지 명령은 연결을 닫기 전에 시뮬레이터에 전달되어야 하고, {@code cancel()}은 {@code Future} 규약대로
 * 차트를 취소해야 한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MockWebServer m_server;
	private RCKSimulation m_sim;

	/** 등록 → 레이아웃 → 시작에 응답하고, 받은 명령과 close 프레임을 순서대로 기록하는 가짜 RCK 서버. */
	private static class FakeRckServer extends okhttp3.WebSocketListener {
		final List<String> m_events = Collections.synchronizedList(new ArrayList<>());
		final CountDownLatch m_closing = new CountDownLatch(1);
		private final boolean m_answerStop;
		private final boolean m_failAfterStart;
		/** 시작 응답 뒤 보낼 메시지들(텍스트 또는 byte[]). */
		private final List<Object> m_afterStart = new ArrayList<>();
		/** layout 요청에 응답하지 않는다(응답 대기 시간 초과 시험용). */
		boolean m_ignoreLayout = false;

		FakeRckServer(boolean answerStop, boolean failAfterStart) {
			m_answerStop = answerStop;
			m_failAfterStart = failAfterStart;
		}

		FakeRckServer thenSend(Object... messages) {
			m_afterStart.addAll(List.of(messages));
			return this;
		}

		@Override
		public void onMessage(okhttp3.WebSocket ws, String text) {
			try {
				JsonNode msg = MAPPER.readTree(text);
				if ( msg.has("register") ) {
					m_events.add("register");
					ws.send("{\"type\":\"connected\"}");
					return;
				}
				String type = msg.path("type").asText();
				m_events.add(type);
				switch ( type ) {
					case "layout":
						if ( !m_ignoreLayout ) {
							ws.send("{\"type\":\"facility\",\"message\":{}}");
						}
						break;
					case "command":
						ws.send("{\"type\":\"simulation_start\"}");
						if ( m_failAfterStart ) {
							ws.send("not a json message");
						}
						for ( Object next: m_afterStart ) {
							if ( next instanceof byte[] bytes ) {
								ws.send(okio.ByteString.of(bytes));
							}
							else {
								ws.send((String)next);
							}
						}
						break;
					case "stop":
						if ( m_answerStop ) {
							ws.send("{\"type\":\"simulation_stop\"}");
						}
						break;
				}
			}
			catch ( Exception e ) {
				m_events.add("error: " + e);
			}
		}

		@Override
		public void onClosing(okhttp3.WebSocket ws, int code, String reason) {
			m_events.add("close");
			ws.close(code, null);
			m_closing.countDown();
		}

		void assertStopBeforeClose() throws InterruptedException {
			assertTrue("connection was not closed", m_closing.await(5, TimeUnit.SECONDS));
			List<String> events = new ArrayList<>(m_events);
			int stop = events.indexOf("stop");
			assertTrue("stop command was not delivered: " + events, stop >= 0);
			assertTrue("stop must precede close: " + events, stop < events.indexOf("close"));
		}
	}

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
		m_server = new MockWebServer();
		m_server.enqueue(response.withWebSocketUpgrade(serverListener));
		m_server.start();

		String url = "ws://" + m_server.getHostName() + ":" + m_server.getPort() + "/rck";
		m_sim = new RCKSimulation(new RCKSimulationContext(url, "test-client", "press", "layout"));
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
	public void requestStop_sends_stop_and_finishes_as_cancelled_state() throws Exception {
		FakeRckServer server = new FakeRckServer(true, false);
		RCKSimulation sim = startSimulation(server, new MockResponse());
		awaitState(sim, "/Running");

		sim.requestStop();

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Stopped", sim.getCurrentState().getPath());
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
	public void requestStop_without_response_finishes_as_cancelled_after_timeout() throws Exception {
		// 시뮬레이터가 simulation_stop으로 응답하지 않아도 대기 시간 뒤 Cancelled로 끝난다.
		FakeRckServer server = new FakeRckServer(false, false);
		RCKSimulation sim = startSimulation(server, new MockResponse());
		sim.setStopResponseTimeout(java.time.Duration.ofMillis(300));
		awaitState(sim, "/Running");

		sim.requestStop();

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Stopped", sim.getCurrentState().getPath());
		server.assertStopBeforeClose();
	}

	private static final String STATUS
		= "{\"type\":\"simulation_status\",\"message\":{\"Progress\":100,"
			+ "\"Conveyor_6\":{\"Production\":7},\"e1\":{\"Name\":\"TP-75 #1\",\"Utilization\":40}}}";
	private static String videoInfo(long fileSize) {
		// 모르는 필드(extra)가 있어도 실패하지 않아야 한다.
		return "{\"type\":\"video_info\",\"status\":\"ready\",\"fileName\":\"v.mp4\",\"fileSize\":" + fileSize
				+ ",\"downloadUrl\":\"http://video/v.mp4\",\"extra\":1}";
	}

	@Test(timeout = 15_000)
	public void video_info_reports_url_and_completes_after_receiving_video() throws Exception {
		// video_info는 한 번만 온다. 받으면 바로 영상 수신으로 가고, 영상을 다 받으면 Completed로 끝난다.
		FakeRckServer server = new FakeRckServer(false, false)
									.thenSend(STATUS, videoInfo(6), new byte[] {1, 2, 3}, new byte[] {4, 5, 6});
		List<String> events = Collections.synchronizedList(new ArrayList<>());
		SimulationObserver observer = new SimulationObserver() {
			@Override
			public void onStatus(RCKSimulationStatus status) {
				events.add("status:" + status.getProgress() + "/" + status.getProduction()
							+ "/" + status.getAverageUtilizations());
			}
			@Override
			public void onVideoReady(String downloadUrl) {
				events.add("video:" + downloadUrl);
			}
			@Override
			public void onStateChanged(String statePath, String displayName) {
				events.add(statePath);
			}
		};
		m_server = new MockWebServer();
		m_server.enqueue(new MockResponse().withWebSocketUpgrade(server));
		m_server.start();
		String url = "ws://" + m_server.getHostName() + ":" + m_server.getPort() + "/rck";
		RCKSimulationContext ctx = new RCKSimulationContext(url, "test-client", "press", "layout");
		m_sim = new RCKSimulation(ctx, observer);
		m_sim.start();

		assertTrue(m_sim.waitForFinished(5, TimeUnit.SECONDS).isCompleted());
		assertEquals("/Completed", m_sim.getCurrentState().getPath());
		assertEquals("http://video/v.mp4", ctx.getVideoInfo().downloadUrl());
		assertEquals(100, ctx.getLatestStatus().getProgress());
		assertTrue(events.toString(), events.contains("status:100/7/{TP-75=40.0}"));
		assertTrue(events.toString(), events.contains("video:http://video/v.mp4"));
		assertTrue(events.toString(), events.indexOf("/ReceivingVideo") < events.indexOf("/Completed"));
		server.assertStopBeforeClose();
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

		assertTrue(sim.waitForFinished(5, TimeUnit.SECONDS).isFailed());
		assertEquals("/Failed", sim.getCurrentState().getPath());
		assertTrue("" + sim.getContext().getFailureCause(),
					sim.getContext().getFailureCause() instanceof java.util.concurrent.TimeoutException);
		server.assertStopBeforeClose();
	}
}
