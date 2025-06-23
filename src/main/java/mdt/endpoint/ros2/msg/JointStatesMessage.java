package mdt.endpoint.ros2.msg;

import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.stream.FStream;

import mdt.FaaastRuntime;
import mdt.aas.DataTypes;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix = "m_")
public class JointStatesMessage extends Ros2Message {
	private JoinStatesPayload m_msg = new JoinStatesPayload();

	protected MsgPayload getPayload() {
		return m_msg;
	}
	
	List<String> getName() {
		return m_msg.getName();
	}
	
	List<Double> getPosition() {
		return m_msg.getPosition();
	}
	
	@Override
	public void update(FaaastRuntime rt, String smId, String path, SubmodelElement target) {
		SubmodelElementCollection smc = (SubmodelElementCollection)target;
		Map<String,SubmodelElement> fields = FStream.from(smc.getValue())
													.tagKey(SubmodelElement::getIdShort)
													.toMap();
		FStream.from(m_msg.m_name)
				.zipWith(FStream.from(m_msg.m_position))
				.forEach(pair -> {
					Property field = (Property)fields.get(pair._1);
					if ( field != null ) {
						field.setValue(DataTypes.DOUBLE.toValueString(pair._2));
					}
				});
	}
	
	@Getter @Setter
	@Accessors(prefix="m_")
	public class JoinStatesPayload extends MsgPayload {
		private List<String> m_name;
		private List<Double> m_position;
		private List<Double> m_velocity;
		private List<Double> m_effort;
	}
}
