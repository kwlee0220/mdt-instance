package mdt.persistence.opcua;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.milo.opcua.sdk.client.AddressSpace;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class PandaRobotOpcUaReader {
	private static final Logger logger = LoggerFactory.getLogger(PandaRobotOpcUaReader.class);
	private final String m_serverUrl;
	private OpcUaClient client;
	private final int namespaceIndex;

	// Structure of the Panda robot data in OPC UA
	private static final String BASE_FOLDER = "panda";
	private static final String JOINT_STATES_FOLDER = "jointstates";
	private static final List<String> JOINT_NAMES
								= Arrays.asList("joint1", "joint2", "joint3", "joint4", "joint5", "joint6", "joint7");

	private static final String END_EFFECTOR_FOLDER = "end_effector";
	private static final String POSITION_FOLDER = "position";
	private static final List<String> POSITION_VARIABLES = Arrays.asList("x", "y", "z");

	private static final String ORIENTATION_FOLDER = "orientation";
	private static final List<String> ORIENTATION_VARIABLES = Arrays.asList("qw", "qx", "qy", "qz");

	public PandaRobotOpcUaReader(String serverUrl, int namespaceIndex) {
	    this.m_serverUrl = serverUrl;
	    this.namespaceIndex = namespaceIndex;
	}

	public void connect() throws Exception {
	    // Discover endpoints
	    List<EndpointDescription> endpoints = DiscoveryClient.getEndpoints(m_serverUrl).get();

	    // Configure and create client
	    OpcUaClientConfigBuilder config = new OpcUaClientConfigBuilder();
	    config.setEndpoint(endpoints.get(0));

	    client = OpcUaClient.create(config.build());
	    client.connect().get();

	    logger.info("Connected to OPC UA server: {}", m_serverUrl);
	}

	public Map<String, Double> readJointStates() throws Exception {
	    Map<String, Double> jointValues = new HashMap<>();

	    for (String jointName : JOINT_NAMES) {
	        String nodePath = String.format("%s.%s.%s", BASE_FOLDER, JOINT_STATES_FOLDER, jointName);
	        NodeId nodeId = new NodeId(namespaceIndex, nodePath);
	        
	        AddressSpace  aspace = client.getAddressSpace();
	        UaVariableNode node = aspace.getVariableNode(new NodeId(2, 3));
	        DataValue dv = node.getValue();

	        DataValue value = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
	        Double jointValue = (Double) value.getValue().getValue();

	        jointValues.put(jointName, jointValue);
	        logger.info("Joint {}: {}", jointName, jointValue);
	    }

	    return jointValues;
	}

	public Map<String, Double> readEndEffectorPosition() throws Exception {
	    Map<String, Double> positionValues = new HashMap<>();

	    for (String posVar : POSITION_VARIABLES) {
	        String nodePath = String.format("%s.%s.%s.%s", BASE_FOLDER, END_EFFECTOR_FOLDER, POSITION_FOLDER, posVar);
	        NodeId nodeId = new NodeId(namespaceIndex, nodePath);

	        DataValue value = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
	        Double posValue = (Double) value.getValue().getValue();

	        positionValues.put(posVar, posValue);
	        logger.info("Position {}: {}", posVar, posValue);
	    }

	    return positionValues;
	}

	public Map<String, Double> readEndEffectorOrientation() throws Exception {
	    Map<String, Double> orientationValues = new HashMap<>();

	    for (String oriVar : ORIENTATION_VARIABLES) {
	        String nodePath = String.format("%s.%s.%s.%s", BASE_FOLDER, END_EFFECTOR_FOLDER, ORIENTATION_FOLDER, oriVar);
	        NodeId nodeId = new NodeId(namespaceIndex, nodePath);

	        DataValue value = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
	        Double oriValue = (Double) value.getValue().getValue();

	        orientationValues.put(oriVar, oriValue);
	        logger.info("Orientation {}: {}", oriVar, oriValue);
	    }

	    return orientationValues;
	}

	public Map<String, Object> readAllPandaData() throws Exception {
	    Map<String, Object> allData = new HashMap<>();

	    allData.put("jointstates", readJointStates());
	    allData.put("position", readEndEffectorPosition());
	    allData.put("orientation", readEndEffectorOrientation());

	    return allData;
	}

	public void disconnect() {
	    if (client != null) {
	        client.disconnect();
	        logger.info("Disconnected from OPC UA server");
	    }
	}

	public static void main(String[] args) {
	    // Example server URL
	    String serverUrl = "opc.tcp://localhost:4840/mdt/panda";
//	    String serverUrl = "opc.tcp://localhost:4840/freeopcua/server/";
	    int namespaceIndex = 2; // Adjust based on your server configuration

	    PandaRobotOpcUaReader reader = new PandaRobotOpcUaReader(serverUrl, namespaceIndex);
	    try {
	        // Connect to the server
	        reader.connect();

	        // Read all data
	        Map<String, Object> pandaData = reader.readAllPandaData();

	        // Display summary
	        Map<String, Double> jointStates = (Map<String, Double>) pandaData.get("jointstates");
	        Map<String, Double> position = (Map<String, Double>) pandaData.get("position");
	        Map<String, Double> orientation = (Map<String, Double>) pandaData.get("orientation");

	        System.out.println("\nPanda Robot Data Summary:");
	        System.out.println("------------------------");
	        System.out.println("Joint States:");
	        jointStates.forEach((joint, value) -> System.out.printf("  %s: %.4f\n", joint, value));

	        System.out.println("End Effector Position:");
	        position.forEach((axis, value) -> System.out.printf("  %s: %.4f\n", axis, value));

	        System.out.println("End Effector Orientation (Quaternion):");
	        orientation.forEach((component, value) -> System.out.printf("  %s: %.4f\n", component, value));

	    } catch (Exception e) {
	        logger.error("Error while reading from OPC UA server", e);
	    } finally {
	        reader.disconnect();
	    }
	}
}
