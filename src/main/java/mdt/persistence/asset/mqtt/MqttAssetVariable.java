package mdt.persistence.asset.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.jetbrains.annotations.Nullable;

import utils.Throwables;

import mdt.MqttServicePool;
import mdt.client.support.MqttBrokerConfig;
import mdt.client.support.MqttService;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AbstractAssetVariable;
import mdt.persistence.asset.AssetVariableException;
import mdt.persistence.asset.mqtt.MqttAssetVariableConfig.DisconnectedPolicy;

import de.fraunhofer.iosb.ilt.faaast.service.starter.InitializationException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MqttAssetVariable extends AbstractAssetVariable<MqttAssetVariableConfig> {
	private MqttBrokerConfig m_brokerConfig;	// initialize()에서 한 번만 해석한 broker 설정
	private MqttServicePool.Lease m_lease;		// 공유 연결 사용권 (initialize() 이후에만 존재)
	private MqttService m_mqtt;
	// 연결이 끊어져 발행을 건너뛰고 있는 중인지 여부 (경고 로그를 한 번만 남기기 위해 사용)
	private final AtomicBoolean m_skipping = new AtomicBoolean(false);

	public MqttAssetVariable(MqttAssetVariableConfig config) {
		super(config);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);

		// 설정에 broker 정보가 없으면 global 설정에서 읽어온다.
		// global 설정은 읽을 때마다 파일을 다시 읽으므로 여기서 한 번만 해석하여 보관한다.
		try {
			m_brokerConfig = m_config.getMqttBrokerConfig();
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InitializationException("Failed to load MQTT broker configuration: " + this, cause);
		}

		// 같은 broker를 사용하는 다른 AssetVariable·MqttEndpoint와 연결을 공유한다.
		m_lease = MqttServicePool.acquire(m_brokerConfig);
		m_mqtt = m_lease.getService();
		getLogger().info("Initialized {}", this);
	}

	@Override
	public void close() {
		// 공유 연결의 사용권을 반납한다. 마지막 사용자가 반납하면 broker와의 연결이 끊어진다.
		// Lease.close()는 여러 번 호출해도 한 번만 반납된다.
		if ( m_lease != null ) {
			m_lease.close();
		}
	}

	@Override
	public boolean isReadable() {
		return false;
	}

	@Override
	public boolean isUpdatable() {
        return true;
    }

	@Override
	public SubmodelElement read() throws AssetVariableException {
		throw new AssetVariableException("read is not supported for MQTT AssetVariable: " + this);
	}

	/**
	 * 주어진 요소의 값을 설정된 topic으로 발행한다.
	 * <p>
	 * 메시지는 설정의 {@code qos}, {@code retained}로 발행하며, QoS 1, 2인 경우는 broker의 전달 확인을
	 * 최대 {@code publishTimeout}까지 기다린다.
	 * broker와 연결되어 있지 않거나 제한 시간 안에 전달 확인을 받지 못하면 설정의 {@code whenDisconnected}
	 * 정책에 따라 처리한다. {@link DisconnectedPolicy#SKIP}이면 발행을 건너뛰고 정상 반환하므로 AAS 요소의
	 * 값은 갱신된다. {@link DisconnectedPolicy#FAIL}이면 {@link AssetVariableException}을 던진다.
	 * 그 밖의 실패(값 변환 실패, 권한 오류 등)는 정책과 상관없이 예외를 던진다.
	 */
	@Override
	public void update(SubmodelElement newElement) throws AssetVariableException {
		MqttMessage message;
		try {
			String valueJson = ElementValues.getValue(newElement).toValueJsonString();
			message = new MqttMessage(valueJson.getBytes(StandardCharsets.UTF_8));
			message.setQos(m_config.getQos());
			message.setRetained(m_config.isRetained());
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to build MQTT message: %s, cause=%s", this, cause);
			throw new AssetVariableException(msg, cause);
		}

		if ( !m_mqtt.isConnected() ) {
			handleUndelivered("MQTT broker is not connected", null);
			return;
		}

		try {
			m_mqtt.publish(m_config.getTopic(), message, m_config.getPublishTimeout());
			if ( m_skipping.getAndSet(false) ) {
				getLogger().info("resumed publishing: {}", this);
			}
		}
		catch ( MqttException e ) {
			// 연결 확인 이후 발행 도중에 연결이 끊어진 경우도 연결되지 않은 경우와 동일하게 처리한다.
			if ( isDisconnectedError(e) ) {
				handleUndelivered("MQTT broker is not connected", e);
			}
			else if ( e.getReasonCode() == MqttException.REASON_CODE_CLIENT_TIMEOUT ) {
				// 메시지가 실제로 전달되었을 수도 있으나 확인하지 못한 경우이다.
				String reason = String.format("MQTT delivery is not confirmed within %s", m_config.getPublishTimeout());
				handleUndelivered(reason, e);
			}
			else {
				String msg = String.format("Failed to publish MQTT message: %s, cause=%s", this, e);
				throw new AssetVariableException(msg, e);
			}
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to publish MQTT message: %s, cause=%s", this, cause);
			throw new AssetVariableException(msg, cause);
		}
	}

	private void handleUndelivered(String reason, @Nullable MqttException cause) throws AssetVariableException {
		if ( m_config.getWhenDisconnected() == DisconnectedPolicy.FAIL ) {
			String msg = String.format("%s: %s", reason, this);
			throw new AssetVariableException(msg, cause);
		}

		// 전달하지 못하는 동안 갱신마다 경고가 쌓이지 않도록 처음 한 번만 경고하고, 이후는 debug로 남긴다.
		// 경고 상태는 발행에 다시 성공하면 해제된다.
		if ( !m_skipping.getAndSet(true) ) {
			getLogger().warn("{}, skip publishing until delivered again: {}", reason, this);
		}
		else {
			getLogger().debug("{}, skip publishing: {}", reason, this);
		}
	}

	private static boolean isDisconnectedError(MqttException e) {
		switch ( e.getReasonCode() ) {
			case MqttException.REASON_CODE_CLIENT_NOT_CONNECTED:
			case MqttException.REASON_CODE_CONNECTION_LOST:
			case MqttException.REASON_CODE_CLIENT_DISCONNECTING:
			case MqttException.REASON_CODE_CLIENT_CLOSED:
				return true;
			default:
				return false;
		}
	}

	/**
	 * 테스트를 위해 {@link MqttService}를 지정한다.
	 *
	 * @param mqtt	사용할 {@link MqttService}.
	 */
	void setMqttService(MqttService mqtt) {
		m_mqtt = mqtt;
	}

	@Override
	public String toString() {
		// broker 설정이 해석된 이후에는 실제 broker URL을 사용한다.
		if ( m_brokerConfig != null ) {
			return String.format("%s[%s, broker=%s, topic=%s]", getClass().getSimpleName(),
									getElementLocation(), m_brokerConfig.getBrokerUrl(), m_config.getTopic());
		}
		else {
			return String.format("%s[%s]", getClass().getSimpleName(), getConfig());
		}
	}
}
