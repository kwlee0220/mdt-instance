package mdt.persistence.asset.opcua;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Throwables;

import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AbstractAssetVariable;
import mdt.persistence.asset.AssetVariableException;
import mdt.persistence.asset.jdbc.SubmodelElementHandler;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OpcUaAssetVariable extends AbstractAssetVariable<OpcUaAssetVariableConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(OpcUaAssetVariable.class);
	private static final String SERVER_ENDPOINT = "opc.tcp://localhost:4840/mdt/panda";
	
	private OpcUaClient m_client;
	private SubmodelElementHandler m_handler;
	
	public OpcUaAssetVariable(OpcUaAssetVariableConfig config) throws Exception {
		super(config);
		
		m_client = connect();
		setLogger(s_logger);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);
		
		m_handler = new SubmodelElementHandler(m_prototype);
	}

	@Override
	public boolean isReadable() {
		return getConfig().isReadable();
	}

	@Override
	public boolean isUpdatable() {
        return false;
    }

	@Override
	public SubmodelElement read() throws AssetVariableException {
		try {
			double value = readNode(m_client, "panda.jointstates.joint2");
			
			m_handler.update(m_prototype, "" + value);
			
			return m_prototype;
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to read OPC-UA node: path=%s, cause=%s",
										"panda.jointstates.joint2", e.getMessage());
			throw new AssetVariableException(msg, cause);
		}
	}

	@Override
	public void update(SubmodelElement newElement) throws AssetVariableException {
		throw new AssetVariableException("update is not supported for OPC-UA node: path=" + "panda.jointstates.joint2");
	}

	private OpcUaClient connect() throws Exception {
	    // Discover endpoints
	    List<EndpointDescription> endpoints = DiscoveryClient.getEndpoints(SERVER_ENDPOINT).get();

	    // Configure and create client
	    OpcUaClientConfigBuilder config = new OpcUaClientConfigBuilder();
	    config.setEndpoint(endpoints.get(0));

	    OpcUaClient client = OpcUaClient.create(config.build());
	    client.connect().get();

	    if ( getLogger().isInfoEnabled() ) {
	    	getLogger().info("Connected to OPC UA server: {}", SERVER_ENDPOINT);
	    }
	    
	    return client;
	}

	private double readNode(OpcUaClient client, String path) throws InterruptedException, ExecutionException {
        NodeId nodeId = new NodeId(2, path);
        DataValue value = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
        return (Double)value.getValue().getValue();
	}
}
