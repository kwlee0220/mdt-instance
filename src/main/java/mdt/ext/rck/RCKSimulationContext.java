package mdt.ext.rck;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.websocket.WebSocketContext;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class RCKSimulationContext extends WebSocketContext {
	private final String m_clientId;
	private final String m_processName;
	private final String m_layoutName;

	private JsonNode m_equipmentProperties;
	private RCKSimulationResult m_simulationResult;
	private VideoInfo m_simulationVideo;
	private Throwable m_failureCause;
	
	RCKSimulationContext(String serverUrl, String clientId, String processName, String layoutName) {
		super(serverUrl);
		
		m_clientId = clientId;
		m_processName = processName;
		m_layoutName = layoutName;
		m_simulationResult = RCKSimulationResult.empty();
	}
	
	@Override
	public boolean equals(Object obj) {
		if ( this == obj ) {
			return true;
		}
		if ( obj == null || !(obj instanceof RCKSimulationContext) ) {
			return false;
		}

		RCKSimulationContext other = (RCKSimulationContext) obj;
		return getServerUrl().equals(other.getServerUrl())
				&& m_processName.equals(other.m_processName)
				&& m_layoutName.equals(other.m_layoutName);
	}
	
	@Override
	public String toString() {
		return "RCKSimulationContext[serverUrl=" + getServerUrl() + ",processName=" + m_processName
									+ ",layoutName=" + m_layoutName + "]";
	}
}
