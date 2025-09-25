package mdt.endpoint.companion;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import utils.async.command.ProgramServiceConfig;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonPropertyOrder({ "programConfig" })
public class ProgramCompanionConfig extends EndpointConfig<ProgramCompanion> {
	private ProgramServiceConfig m_programConfig;
	
	public ProgramServiceConfig getProgramConfig() {
		return m_programConfig;
	}
	public void setProgramConfig(ProgramServiceConfig programConfig) {
		m_programConfig = programConfig;
	}
}
