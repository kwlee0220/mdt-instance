package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import de.fraunhofer.iosb.ilt.faaast.service.model.api.StatusCode;

import de.fraunhofer.iosb.ilt.faaast.service.model.value.PropertyValue;

import mdt.ElementLocation;
import mdt.ElementUpdateException;
import mdt.ElementValueUpdater;


/**
 * {@link MqttSubscriber}가 payload를 갱신기로 넘기고, 실패를 토픽·요소가 담긴 로그로 남기는지 검증한다.
 * 구독을 만들 때 대상 요소가 없는 경우는 {@link MqttEndpointSubscriptionTest}에서 다룬다.
 * broker와 FA³ST 없이 가짜 갱신기를 사용하며, 반영은 호출 쓰레드에서 바로 실행한다(direct executor).
 * 작업 쓰레드에서의 반영 방식은 {@link MqttSubscriberDeliveryTest}에서 다룬다.
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
		MqttSubscriber subscriber = new MqttSubscriber(MAPPING, updater(MAPPING.getElementLocation(), received::add),
												MoreExecutors.directExecutor(), MqttSubscriber.DEFAULT_CAPACITY);
		
		subscriber.onMessage(MAPPING.getTopic(), message("23.5"));
		subscriber.onMessage(MAPPING.getTopic(), message("24.0"));
		
		assertEquals(List.of("23.5", "24.0"), received);
		assertTrue(errors().isEmpty());
	}

	@Test
	public void testTextPayloadIsQuoted() {
		List<String> received = Lists.newArrayList();
		MqttElementSubscriber mapping = new MqttElementSubscriber("factory/robot01/status", "State:Status", null, null,
																	PayloadFormat.TEXT);
		MqttSubscriber subscriber = new MqttSubscriber(mapping, updater(mapping.getElementLocation(), received::add),
														MoreExecutors.directExecutor(), MqttSubscriber.DEFAULT_CAPACITY);
		
		subscriber.onMessage(mapping.getTopic(), message("running"));
		subscriber.onMessage(mapping.getTopic(), message("say \"hi\"\\n"));
		
		// 일반 텍스트를 JSON 문자열로 감싸고, 따옴표와 역슬래시는 이스케이프한다.
		assertEquals(List.of("\"running\"", "\"say \\\"hi\\\"\\\\n\""), received);
	}

	@Test
	public void testUpdateFailureIsLoggedWithTopicAndElement() {
		MqttSubscriber subscriber = new MqttSubscriber(MAPPING, updater(MAPPING.getElementLocation(), json -> {
			throw new ElementUpdateException("failed to update element: status=CLIENT_ERROR_BAD_REQUEST",
											StatusCode.CLIENT_ERROR_BAD_REQUEST);
		}), MoreExecutors.directExecutor(), MqttSubscriber.DEFAULT_CAPACITY);
		
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
	public void testRepeatedFailuresAreRateLimited() {
		MqttSubscriber subscriber = new MqttSubscriber(MAPPING, updater(MAPPING.getElementLocation(), json -> {
			throw new ElementUpdateException("bad value", StatusCode.CLIENT_ERROR_BAD_REQUEST);
		}), MoreExecutors.directExecutor(), MqttSubscriber.DEFAULT_CAPACITY);
		
		for ( int i = 0; i < 100; ++i ) {
			subscriber.onMessage(MAPPING.getTopic(), message("x"));
		}
		
		// 연속 실패의 1번째, 10번째, 100번째만 ERROR로 남는다.
		List<ILoggingEvent> errors = errors();
		assertEquals(3, errors.size());
		assertTrue(errors.get(1).getFormattedMessage(), errors.get(1).getFormattedMessage().contains("10 consecutive failures"));
		assertTrue(errors.get(2).getFormattedMessage(), errors.get(2).getFormattedMessage().contains("100 consecutive failures"));
		assertTrue(errors.get(2).getFormattedMessage().contains("State:Temperature"));
		assertEquals(100, subscriber.getConsecutiveFailureCount());
	}

	@Test
	public void testRecoveryIsLoggedAndResetsStreak() {
		AtomicBoolean failing = new AtomicBoolean(true);
		MqttSubscriber subscriber = new MqttSubscriber(MAPPING, updater(MAPPING.getElementLocation(), json -> {
			if ( failing.get() ) {
				throw new ElementUpdateException("bad value", StatusCode.CLIENT_ERROR_BAD_REQUEST);
			}
		}), MoreExecutors.directExecutor(), MqttSubscriber.DEFAULT_CAPACITY);
		
		for ( int i = 0; i < 3; ++i ) {
			subscriber.onMessage(MAPPING.getTopic(), message("x"));
		}
		failing.set(false);
		subscriber.onMessage(MAPPING.getTopic(), message("1.0"));
		
		assertEquals(0, subscriber.getConsecutiveFailureCount());
		List<String> infos = messages(Level.INFO);
		assertEquals(1, infos.stream().filter(msg -> msg.contains("recovered after 3 consecutive failures")).count());
		
		// 회복한 뒤의 실패는 다시 첫 번째 실패로 ERROR가 남는다.
		failing.set(true);
		subscriber.onMessage(MAPPING.getTopic(), message("x"));
		assertEquals(2, errors().size());
	}

	@Test
	public void testReminderCounts() {
		for ( long n: new long[] { 10, 100, 1000, 10_000 } ) {
			assertTrue(String.valueOf(n), MqttSubscriber.isReminderCount(n));
		}
		for ( long n: new long[] { 1, 2, 9, 11, 20, 50, 101, 110, 200, 999 } ) {
			assertFalse(String.valueOf(n), MqttSubscriber.isReminderCount(n));
		}
	}
	
	private List<String> messages(Level level) {
		synchronized ( m_appender ) {
			return m_appender.list.stream().filter(ev -> ev.getLevel() == level)
										.map(ILoggingEvent::getFormattedMessage).toList();
		}
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
			@Override public Class<?> getValueType() { return PropertyValue.class; }
			@Override public void update(String valueJson) { action.accept(valueJson); }
		};
	}
}
