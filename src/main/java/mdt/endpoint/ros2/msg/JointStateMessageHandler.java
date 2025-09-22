package mdt.endpoint.ros2.msg;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import utils.KeyValue;
import utils.stream.FStream;
import utils.stream.KeyValueFStream;

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
@JsonPropertyOrder({ "topic", "element", "mappings" })
public class JointStateMessageHandler implements Ros2MessageHandler<JointStatesMessage> {
	private final String m_topic;
	private final ElementLocation m_elementLoc;
	private final Map<String,String> m_nameToSubpaths;
	
	private FaaastRuntime m_faaast;
	private SubmodelElement m_buffer;
	
	private JointStateMessageHandler(@JsonProperty("topic") String topic,
									@JsonProperty("element") String elementRefExpr,
									@JsonProperty("mappings") List<Mapping> mappings) {
		m_topic = topic;
		m_elementLoc = ElementLocations.parseStringExpr(elementRefExpr);
		m_nameToSubpaths = FStream.from(mappings)
									.mapToKeyValue(m -> KeyValue.of(m.getName(), m.getSubPath()))
									.toMap();
	}

	@Override
	public String getTopic() {
		return m_topic;
	}
	
	public ElementLocation getElement() {
		return m_elementLoc;
	}
	
	public List<Mapping> getMappings() {
		return KeyValueFStream.from(m_nameToSubpaths)
								.map((k,v) -> new Mapping(k, v))
								.toList();
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
	
	@JsonPropertyOrder({ "name", "subPath" })
	public static class Mapping {
		public String m_name;
		public String m_subPath;
		
		public Mapping(@JsonProperty("name") String name, @JsonProperty("subPath") String subPath) {
			this.m_name = name;
			this.m_subPath = subPath;
		}
		
		public String getName() {
			return m_name;
		}
		
		public String getSubPath() {
			return m_subPath;
		}
	}
}
