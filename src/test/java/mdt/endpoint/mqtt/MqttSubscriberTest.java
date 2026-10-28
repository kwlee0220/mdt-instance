package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import de.fraunhofer.iosb.ilt.faaast.service.model.api.StatusCode;

import mdt.ElementLocation;
import mdt.ElementUpdateException;
import mdt.ElementValueUpdater;


/**
 * {@link MqttSubscriber}가 payload를 갱신기로 넘기고, 실패를 토픽·요소가 담긴 로그로 남기는지 검증한다.
 * broker와 FA³ST 없이 가짜 갱신기를 사용한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttSubscriberTest {
	private static final MqttElementSubscriber MAPPING
										= new MqttElementSubscriber("factory/robot01/temperature", "State:Temperature");
	
	private Logger m_logger;
	private ListAppender<ILoggingEvent> m_appender;
	
	@Before
	public void setUp() {
		m_logger = (Logger)LoggerFactory.getLogger(MqttEndpoint.class);
		m_appender = new ListAppender<>();
		m_appender.start();
		m_logger.addAppender(m_appender);
	}
	
	@After
	public void tearDown() {
		m_logger.detachAppender(m_appender);
	}

	@Test
	public void testPayloadIsPassedToUpdater() {
		List<String> received = Lists.newArrayList();
		AtomicInteger created = new AtomicInteger();
		MqttSubscriber subscriber = new MqttSubscriber(MAPPING, loc -> {
			created.incrementAndGet();
			return updater(loc, received::add);
		});
		
		subscriber.onMessage(MAPPING.getTopic(), message("23.5"));
		subscriber.onMessage(MAPPING.getTopic(), message("24.0"));
		
		assertEquals(List.of("23.5", "24.0"), received);
		assertEquals("the updater must be created once and reused", 1, created.get());
		assertTrue(errors().isEmpty());
	}

	@Test
	public void testUpdateFailureIsLoggedWithTopicAndElement() {
		MqttSubscriber subscriber = new MqttSubscriber(MAPPING, loc -> updater(loc, json -> {
			throw new ElementUpdateException("failed to update element: status=CLIENT_ERROR_BAD_REQUEST",
											StatusCode.CLIENT_ERROR_BAD_REQUEST);
		}));
		
		// 실패해도 예외가 밖으로 나가지 않아야 다음 메시지를 계속 처리한다.
		subscriber.onMessage(MAPPING.getTopic(), message("secret-payload"));
		
		List<ILoggingEvent> errors = errors();
		assertEquals(1, errors.size());
		String msg = errors.get(0).getFormattedMessage();
		assertTrue(msg, msg.contains("factory/robot01/temperature"));
		assertTrue(msg, msg.contains("State:Temperature"));
		assertTrue(msg, msg.contains("CLIENT_ERROR_BAD_REQUEST"));
		assertFalse("payload must not be logged", msg.contains("secret-payload"));
	}

	@Test
	public void testUpdaterCreationFailureIsLogged() {
		MqttSubscriber subscriber = new MqttSubscriber(MAPPING, loc -> {
			throw new ElementUpdateException("element not found: State:Temperature", (StatusCode)null);
		});
		
		subscriber.onMessage(MAPPING.getTopic(), message("23.5"));
		
		List<ILoggingEvent> errors = errors();
		assertEquals(1, errors.size());
		assertTrue(errors.get(0).getFormattedMessage().contains("element not found"));
	}
	
	private List<ILoggingEvent> errors() {
		synchronized ( m_appender ) {
			return m_appender.list.stream().filter(ev -> ev.getLevel() == Level.ERROR).toList();
		}
	}
	
	private static MqttMessage message(String payload) {
		return new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
	}
	
	private static ElementValueUpdater updater(ElementLocation loc, java.util.function.Consumer<String> action) {
		return new ElementValueUpdater() {
			@Override public ElementLocation getLocation() { return loc; }
			@Override public void update(String valueJson) { action.accept(valueJson); }
		};
	}
}
