package mdt.ext.rck;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/** 등록 → 레이아웃 → 시작에 응답하고, 받은 명령과 close 프레임을 순서대로 기록하는 가짜 RCK 서버. */
class FakeRckServer extends okhttp3.WebSocketListener {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	final List<String> m_events = Collections.synchronizedList(new ArrayList<>());
	final CountDownLatch m_closing = new CountDownLatch(1);
	private final boolean m_answerStop;
	private final boolean m_failAfterStart;
	/** 시작 응답 뒤 보낼 메시지들(텍스트 또는 byte[]). */
	private final List<Object> m_afterStart = new ArrayList<>();
	/** layout 요청에 응답하지 않는다(응답 대기 시간 초과 시험용). */
	boolean m_ignoreLayout = false;
	/** 시작 명령에 응답하기 전에 기다릴 시간(밀리초). 비동기 호출이 기다리지 않는지 시험할 때 쓴다. */
	volatile long m_startDelayMillis = 0;

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
					if ( m_startDelayMillis > 0 ) {
						Thread.sleep(m_startDelayMillis);
					}
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
