package mdt.persistence.opcua;

import java.time.Duration;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.milo.opcua.sdk.client.api.UaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
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
			m_opcuaConnConfig = MDTGlobalConfigurations.getOpcUaConnectionConfig("default");
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
	public boolean isUpdateable() {
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
}
