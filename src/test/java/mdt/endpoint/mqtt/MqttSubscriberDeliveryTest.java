package mdt.endpoint.mqtt;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import mdt.ElementLocation;
import mdt.ElementValueUpdater;


/**
 * {@link MqttSubscriber}가 반영 작업을 작업 쓰레드로 넘기고, {@link DeliveryMode}에 따라
 * 모든 메시지 또는 최신 값만 반영하는지 검증한다.
 * <p>
 * 첫 반영을 latch로 붙잡아 반영이 밀린 상황을 만든다. 콜백 쓰레드(테스트 쓰레드)가 붙잡히면 테스트는 시간 초과로 실패한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttSubscriberDeliveryTest {
	private static final String TOPIC = "factory/robot01/temperature";

	private ExecutorService m_workers;
	private Logger m_logger;
	private ListAppender<ILoggingEvent> m_appender;

	private final List<String> m_applied = new CopyOnWriteArrayList<>();
	private final CountDownLatch m_firstStarted = new CountDownLatch(1);
	private final CountDownLatch m_release = new CountDownLatch(1);

	@Before
	public void setUp() {
		m_workers = Executors.newCachedThreadPool();
		m_logger = (Logger)LoggerFactory.getLogger(MqttEndpoint.class);
		m_appender = new ListAppender<>();
		m_appender.start();
		m_logger.addAppender(m_appender);
	}

	@After
	public void tearDown() throws InterruptedException {
		m_release.countDown();
		m_workers.shutdownNow();
		m_workers.awaitTermination(5, TimeUnit.SECONDS);
		m_logger.detachAppender(m_appender);
	}

	@Test(timeout = 10_000)
	public void testLatestKeepsOnlyNewestPendingValue() throws Exception {
		MqttSubscriber subscriber = subscriber(DeliveryMode.LATEST, MqttSubscriber.DEFAULT_CAPACITY);

		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));

		// 첫 반영이 붙잡혀 있어도 콜백은 바로 반환한다. 2, 3은 4로 덮여 버려진다.
		subscriber.onMessage(TOPIC, message("2"));
		subscriber.onMessage(TOPIC, message("3"));
		subscriber.onMessage(TOPIC, message("4"));

		m_release.countDown();
		awaitApplied(2);
		Thread.sleep(100);		// 더 반영되는 값이 없는지 확인한다.
		assertEquals(List.of("1", "4"), m_applied);
		assertEquals(2, subscriber.getDroppedCount());
	}

	@Test(timeout = 10_000)
	public void testAllAppliesEveryMessageInOrder() throws Exception {
		MqttSubscriber subscriber = subscriber(DeliveryMode.ALL, MqttSubscriber.DEFAULT_CAPACITY);

		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));
		subscriber.onMessage(TOPIC, message("2"));
		subscriber.onMessage(TOPIC, message("3"));
		subscriber.onMessage(TOPIC, message("4"));

		m_release.countDown();
		awaitApplied(4);
		assertEquals(List.of("1", "2", "3", "4"), m_applied);
		assertEquals(0, subscriber.getDroppedCount());
	}

	@Test(timeout = 10_000)
	public void testAllWaitsWhenBacklogIsFull() throws Exception {
		// 대기열 크기 1: 첫 메시지가 반영 중인 동안 둘째 메시지는 자리가 날 때까지 기다려야 한다(버리지 않음).
		MqttSubscriber subscriber = subscriber(DeliveryMode.ALL, 1);

		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));

		Thread callback = new Thread(() -> subscriber.onMessage(TOPIC, message("2")));
		callback.start();
		callback.join(300);
		assertTrue("the callback must wait for a free slot", callback.isAlive());

		m_release.countDown();
		callback.join(5_000);
		assertFalse(callback.isAlive());
		awaitApplied(2);
		assertEquals(List.of("1", "2"), m_applied);
		assertEquals(1, warnings().stream().filter(msg -> msg.contains("backlog is full")).count());
	}

	@Test(timeout = 10_000)
	public void testCloseDiscardsPendingMessages() throws Exception {
		MqttSubscriber subscriber = subscriber(DeliveryMode.LATEST, MqttSubscriber.DEFAULT_CAPACITY);

		subscriber.onMessage(TOPIC, message("1"));
		assertTrue(m_firstStarted.await(5, TimeUnit.SECONDS));
		subscriber.onMessage(TOPIC, message("2"));		// 반영 대기

		subscriber.close();
		subscriber.onMessage(TOPIC, message("3"));		// close 이후에는 받지 않는다.
		m_release.countDown();

		m_workers.shutdown();
		assertTrue(m_workers.awaitTermination(5, TimeUnit.SECONDS));
		assertEquals("only the update in progress completes", List.of("1"), m_applied);
	}

	private MqttSubscriber subscriber(DeliveryMode delivery, int capacity) {
		MqttElementSubscriber mapping = new MqttElementSubscriber(TOPIC, "State:Temperature", delivery);
		ElementLocation loc = mapping.getElementLocation();
		ElementValueUpdater updater = new ElementValueUpdater() {
			@Override public ElementLocation getLocation() { return loc; }
			@Override public void update(String valueJson) {
				if ( m_firstStarted.getCount() > 0 ) {
					m_firstStarted.countDown();
					try {
						m_release.await();
					}
					catch ( InterruptedException e ) {
						Thread.currentThread().interrupt();
					}
				}
				m_applied.add(valueJson);
			}
		};
		return new MqttSubscriber(mapping, updater, m_workers, capacity);
	}

	private void awaitApplied(int count) throws InterruptedException {
		while ( m_applied.size() < count ) {
			Thread.sleep(10);
		}
	}

	private List<String> warnings() {
		synchronized ( m_appender ) {
			return m_appender.list.stream()
								.filter(ev -> ev.getLevel() == Level.WARN)
								.map(ILoggingEvent::getFormattedMessage)
								.toList();
		}
	}

	private static MqttMessage message(String payload) {
		return new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
	}
}
