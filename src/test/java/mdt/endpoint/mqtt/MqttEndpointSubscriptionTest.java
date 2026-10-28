package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.MoreExecutors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import de.fraunhofer.iosb.ilt.faaast.service.model.api.StatusCode;

import de.fraunhofer.iosb.ilt.faaast.service.model.value.PropertyValue;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.SubmodelElementCollectionValue;

import mdt.ElementLocation;
import mdt.ElementUpdateException;
import mdt.ElementValueUpdater;


/**
 * {@link MqttEndpoint#createSubscribers}가 대상 요소를 찾지 못한 구독을 ERROR 로그와 함께 빼고,
 * 나머지 구독은 그대로 만드는지 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttEndpointSubscriptionTest {
	private static final MqttElementSubscriber TEMPERATURE
										= new MqttElementSubscriber("factory/robot01/temperature", "State:Temperature");
	private static final MqttElementSubscriber TYPO
										= new MqttElementSubscriber("factory/robot01/pressure", "State:Presure");
	private static final MqttElementSubscriber STATUS
										= new MqttElementSubscriber("factory/robot01/status", "State:Status");
	private static final Set<String> EXISTING = Set.of("Temperature", "Status");
	
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
	public void testMissingElementIsSkippedWithError() {
		List<MqttSubscriber> subscribers = MqttEndpoint.createSubscribers(List.of(TEMPERATURE, TYPO, STATUS),
																			MqttEndpointSubscriptionTest::updaterOf,
																	MoreExecutors.directExecutor());
		
		// 요소가 없는 구독만 빠지고, 나머지는 설정 순서대로 남는다.
		assertEquals(List.of("factory/robot01/temperature", "factory/robot01/status"),
					subscribers.stream().map(MqttSubscriber::getTopic).toList());
		
		List<ILoggingEvent> errors = errors();
		assertEquals(1, errors.size());
		String msg = errors.get(0).getFormattedMessage();
		assertTrue(msg, msg.startsWith("skipping MQTT subscription"));
		assertTrue(msg, msg.contains("factory/robot01/pressure"));
		assertTrue(msg, msg.contains("State:Presure"));
		assertTrue(msg, msg.contains("element not found"));
	}

	@Test
	public void testLocationResolutionFailureIsSkipped() {
		// 요소 위치 해석 자체가 실패한 경우(예: 없는 Submodel이나 파라미터)도 같은 방식으로 뺀다.
		List<MqttSubscriber> subscribers = MqttEndpoint.createSubscribers(List.of(TEMPERATURE, STATUS), loc -> {
			if ( loc.getElementPath().equals("Status") ) {
				throw new IllegalArgumentException("unknown parameter: Status");
			}
			return updaterOf(loc);
		}, MoreExecutors.directExecutor());
		
		assertEquals(1, subscribers.size());
		assertEquals(1, errors().size());
		assertTrue(errors().get(0).getFormattedMessage().contains("unknown parameter"));
	}

	@Test
	public void testAllSubscriptionsSkipped() {
		List<MqttSubscriber> subscribers = MqttEndpoint.createSubscribers(List.of(TYPO),
																			MqttEndpointSubscriptionTest::updaterOf,
																	MoreExecutors.directExecutor());
		assertTrue(subscribers.isEmpty());
		assertEquals(1, errors().size());
	}
	
	@Test
	public void testTextFormatRequiresProperty() {
		MqttElementSubscriber textToProperty = new MqttElementSubscriber("t/status", "State:Status", null, null,
																		PayloadFormat.TEXT);
		MqttElementSubscriber textToCollection = new MqttElementSubscriber("t/joints", "State:Joints", null, null,
																		PayloadFormat.TEXT);
		MqttElementSubscriber jsonToCollection = new MqttElementSubscriber("t/joints2", "State:Joints", null, null,
																		null);
		List<MqttSubscriber> subscribers = MqttEndpoint.createSubscribers(
								List.of(textToProperty, textToCollection, jsonToCollection),
								loc -> updaterOf(loc, loc.getElementPath().equals("Joints")
															? SubmodelElementCollectionValue.class : PropertyValue.class),
								MoreExecutors.directExecutor());
		
		// Collection에 text 형식을 쓴 구독만 빠진다. Collection이라도 json 형식은 그대로 둔다.
		assertEquals(List.of("t/status", "t/joints2"), subscribers.stream().map(MqttSubscriber::getTopic).toList());
		assertEquals(1, errors().size());
		String msg = errors().get(0).getFormattedMessage();
		assertTrue(msg, msg.contains("t/joints") && msg.contains("format 'text' requires a Property"));
	}
	
	private static ElementValueUpdater updaterOf(ElementLocation loc, Class<?> valueType) {
		return new ElementValueUpdater() {
			@Override public ElementLocation getLocation() { return loc; }
			@Override public Class<?> getValueType() { return valueType; }
			@Override public void update(String valueJson) { }
		};
	}

	// 요소 경로가 EXISTING에 있으면 갱신기를 만들고, 없으면 FaaastRuntime.newValueUpdater()처럼 실패한다.
	private static ElementValueUpdater updaterOf(ElementLocation loc) {
		if ( !EXISTING.contains(loc.getElementPath()) ) {
			throw new ElementUpdateException("element not found: " + loc.toStringExpr(), (StatusCode)null);
		}
		return new ElementValueUpdater() {
			@Override public ElementLocation getLocation() { return loc; }
			@Override public Class<?> getValueType() { return PropertyValue.class; }
			@Override public void update(String valueJson) { }
		};
	}
	
	private List<ILoggingEvent> errors() {
		synchronized ( m_appender ) {
			return m_appender.list.stream().filter(ev -> ev.getLevel() == Level.ERROR).toList();
		}
	}
}
