package mdt.endpoint.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Preconditions;

import mdt.ElementLocation;
import mdt.ElementUpdateException;
import mdt.ElementValueUpdater;
import mdt.client.support.MqttService;


/**
 * MQTT 메시지의 payload를 AAS 요소 값으로 반영하는 구독자.
 * <p>
 * payload는 UTF-8 문자열로 읽은 AAS value-only JSON이며, {@link ElementValueUpdater}로 요소에 반영한다.
 * 갱신기는 첫 메시지를 받을 때 만들어 이후 재사용한다. 갱신에 실패하면 토픽과 대상 요소를 담은 ERROR 로그를 남기고
 * 다음 메시지를 계속 처리한다.
 * <p>
 * {@link MqttService}는 한 연결의 메시지를 콜백 쓰레드 하나에서 순서대로 전달하므로, 본 객체는 동시에 호출되지 않는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
class MqttSubscriber implements MqttService.Subscriber {
	private static final Logger s_logger = LoggerFactory.getLogger(MqttEndpoint.class);

	private final MqttElementSubscriber m_mapping;
	private final Function<ElementLocation,ElementValueUpdater> m_updaterFactory;
	private ElementValueUpdater m_updater = null;

	/**
	 * 구독자를 만든다.
	 *
	 * @param mapping			토픽과 대상 요소.
	 * @param updaterFactory	대상 요소의 값 갱신기를 만드는 함수. 보통 {@code FaaastRuntime::newValueUpdater}.
	 */
	MqttSubscriber(MqttElementSubscriber mapping, Function<ElementLocation,ElementValueUpdater> updaterFactory) {
		Preconditions.checkNotNullArgument(mapping, "mapping is null");
		Preconditions.checkNotNullArgument(updaterFactory, "updaterFactory is null");

		m_mapping = mapping;
		m_updaterFactory = updaterFactory;
	}

	@Override
	public void onMessage(String topic, MqttMessage message) {
		String json = new String(message.getPayload(), StandardCharsets.UTF_8);
		try {
			if ( m_updater == null ) {
				m_updater = m_updaterFactory.apply(m_mapping.getElementLocation());
			}
			m_updater.update(json);
		}
		catch ( ElementUpdateException e ) {
			// payload에는 비밀 값이 있을 수 있으므로 로그에 남기지 않는다.
			s_logger.error("failed to update element from MQTT message: topic={}, element={}, cause={}",
							topic, m_mapping.getElementLocationExpr(), e.getMessage());
		}
	}
	
	@Override
	public String toString() {
		return String.format("MqttSubscriber[%s -> %s]", m_mapping.getTopic(), m_mapping.getElementLocationExpr());
	}
}
