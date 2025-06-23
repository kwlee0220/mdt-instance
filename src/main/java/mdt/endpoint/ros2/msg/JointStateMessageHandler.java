package mdt.endpoint.ros2.msg;

import java.io.IOException;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import com.fasterxml.jackson.databind.JsonNode;

import utils.KeyValue;
import utils.stream.FStream;

import mdt.ElementLocation;
import mdt.ElementLocations;
import mdt.FaaastRuntime;
import mdt.aas.DataTypes;
import mdt.model.MDTModelSerDe;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.MDTModelLookup;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JointStateMessageHandler implements Ros2MessageHandler<JointStatesMessage> {
	public static final String SERIALIZATION_TYPE = "mdt.endpoint.ros2.msg.JoinStatesMessage";
	
	private final String m_topic;
	private final ElementLocation m_elementLoc;
	private final Map<String,String> m_nameToSubpaths;
	
	private FaaastRuntime m_faaast;
	private SubmodelElement m_buffer;
	
	private JointStateMessageHandler(String topic, ElementLocation elementLoc, Map<String,String> mappings) {
		m_topic = topic;
		m_elementLoc = elementLoc;
		m_nameToSubpaths = mappings;
	}

	@Override
	public String getTopic() {
		return m_topic;
	}

	@Override
	public String getMessageType() {
		return JointStatesMessage.class.getName();
	}

	@Override
	public void initialize(FaaastRuntime faaast) {
		m_faaast = faaast;
		m_elementLoc.activate(MDTModelLookup.getInstance());
		m_buffer = faaast.getSubmodelElementOfLocation(m_elementLoc);
	}

	@Override
	public JointStatesMessage readMessage(String message) throws IOException {
		return MDTModelSerDe.getJsonMapper().readValue(message, JointStatesMessage.class);
	}

	@Override
	public void update(JointStatesMessage msg) {
		FStream.from(msg.getName())
				.zipWith(FStream.from(msg.getPosition()))
				.forEach(pair -> {
					String name = pair._1;
					Double position = pair._2;
					
					String subPath = m_nameToSubpaths.get(name);
					if ( subPath != null ) {
						Property field = SubmodelUtils.traverse(m_buffer, subPath, Property.class);
						if ( field != null ) {
							field.setValue(DataTypes.DOUBLE.toValueString(position));
						}
					}
				});
		ElementValue ev = ElementValues.getValue(m_buffer);
		m_faaast.updateSubmodelElementValue(m_elementLoc.getSubmodelId(), m_elementLoc.getElementPath(), m_buffer);
	}

	public static JointStateMessageHandler deserializeFields(JsonNode jnode) {
		String topic = jnode.get("topic").asText();
		String elmLocStr = jnode.get("element").asText();
		ElementLocation elementLoc = ElementLocations.parseStringExpr(elmLocStr);
		
		Map<String,String> mappings = FStream.from(jnode.get("mappings").elements())
											.mapToKeyValue(m -> KeyValue.of(m.get("name").asText(), m.get("subPath").asText()))
											.toMap();
		
		return new JointStateMessageHandler(topic, elementLoc, mappings);
	}
}
