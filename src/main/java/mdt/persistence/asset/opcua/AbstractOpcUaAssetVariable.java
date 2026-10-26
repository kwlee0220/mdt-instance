package mdt.persistence.asset.opcua;

import java.time.Duration;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.milo.opcua.sdk.client.api.UaClient;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Throwables;

import mdt.MDTGlobalConfigurations;
import mdt.persistence.asset.AbstractAssetVariable;
import mdt.persistence.asset.AssetVariableConfig;
import mdt.persistence.asset.AssetVariableException;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractOpcUaAssetVariable<T extends AssetVariableConfig> extends AbstractAssetVariable<T> {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractOpcUaAssetVariable.class);
	
//	protected final OpcUaClient m_client;
	private OpcUaConnectionConfig m_opcuaConnConfig;
	protected final AutoReconnectingOpcUaClient m_reconnectingClient;
	
	public AbstractOpcUaAssetVariable(T config) throws ConfigurationInitializationException {
		super(config);
		
		try {
			m_opcuaConnConfig = MDTGlobalConfigurations.getOpcUaConfig("default");
		}
		catch ( Exception e ) {
			throw new ConfigurationInitializationException("Failed to read global configuration, cause=" + e);
		}
		
		try {
			m_reconnectingClient = connect(m_opcuaConnConfig);
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to connect OPC-UA Server: endpoint=%s, cause=%s",
											m_opcuaConnConfig.getServerEndpoint(), cause);
			throw new ConfigurationInitializationException(msg);
		}
		
		setLogger(s_logger);
	}

	@Override
	public boolean isUpdatable() {
        return false;
    }

	@Override
	public void update(SubmodelElement newElement) throws AssetVariableException {
		throw new AssetVariableException("update is not supported for OPC-UA AssetVariable");
	}

	private AutoReconnectingOpcUaClient connect(OpcUaConnectionConfig serverConfig) throws Exception {
		AutoReconnectingOpcUaClient client = new AutoReconnectingOpcUaClient(serverConfig.getServerEndpoint(), "5s");
		client.startAsync();
		
		
//	    // Discover endpoints
//	    List<EndpointDescription> endpoints = DiscoveryClient.getEndpoints(serverConfig.getServerEndpoint()).get();
//
//	    // Configure and create client
//	    OpcUaClientConfigBuilder config = new OpcUaClientConfigBuilder();
//	    config.setEndpoint(endpoints.get(0));
//
//	    OpcUaClient client = OpcUaClient.create(config.build());
//	    client.connect().get();
//
//	    if ( getLogger().isInfoEnabled() ) {
//	    	getLogger().info("Connected to OPC UA server: {}", serverConfig.getServerEndpoint());
//	    }
	    
	    return client;
	}

	protected Double readNode(int opcuaId) throws AssetVariableException {
        try {
			NodeId nodeId = new NodeId(2, opcuaId);
			
			UaClient client = m_reconnectingClient.waitOpcUaClient(Duration.ofSeconds(5));
			DataValue value = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
			
			return (Double)value.getValue().getValue();
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to read OPC-UA node: endpoint=%s, identifier=%d, cause=%s",
										m_opcuaConnConfig.getServerEndpoint(), opcuaId, ""+e);
			throw new AssetVariableException(msg, cause);
		}
	}

	/**
	 * 주어진 노드의 현재 값을 읽는다.
	 * <p>
	 * OPC-UA 서버에 연결되어 있지 않으면 최대 5초간 연결을 기다린다.
	 *
	 * @param nodeId	읽을 노드의 식별자.
	 * @return	노드 값. 값의 타입은 노드의 데이터 타입을 따른다.
	 * @throws AssetVariableException	연결 대기 시간이 초과되었거나, 노드가 없는 등 읽기가 실패한 경우.
	 */
	protected Object readNodeValue(NodeId nodeId) throws AssetVariableException {
		try {
			UaClient client = m_reconnectingClient.waitOpcUaClient(Duration.ofSeconds(5));
			DataValue value = client.readValue(0, TimestampsToReturn.Both, nodeId).get();

			// 노드가 없거나 읽을 수 없으면 예외 대신 Bad 상태 코드와 null 값이 오므로 오류로 바꾼다.
			StatusCode status = value.getStatusCode();
			if ( status != null && status.isBad() ) {
				throw new UaException(status);
			}

			return value.getValue().getValue();
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to read OPC-UA node: endpoint=%s, node=%s, cause=%s",
										m_opcuaConnConfig.getServerEndpoint(), nodeId.toParseableString(), ""+e);
			throw new AssetVariableException(msg, cause);
		}
	}
}
