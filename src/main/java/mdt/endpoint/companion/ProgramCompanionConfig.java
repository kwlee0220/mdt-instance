package mdt.endpoint.companion;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import utils.async.command.ProgramServiceConfig;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonPropertyOrder({ "programConfig", "required" })
public class ProgramCompanionConfig extends EndpointConfig<ProgramCompanion> {
	private ProgramServiceConfig m_programConfig;
	private boolean m_required = false;
	
	public ProgramServiceConfig getProgramConfig() {
		return m_programConfig;
	}
	public void setProgramConfig(ProgramServiceConfig programConfig) {
		m_programConfig = programConfig;
	}
	
	/**
	 * 동반 프로그램이 인스턴스 시작에 반드시 필요한지 여부를 반환한다.
	 * {@code true}이면 프로그램이 시작되지 못할 때 {@link ProgramCompanion#start()}가 실패한다.
	 *
	 * @return	필수이면 {@code true}. 기본값은 {@code false}.
	 */
	public boolean isRequired() {
		return m_required;
	}
	public void setRequired(boolean required) {
		m_required = required;
	}
	
	@Override
	public String toString() {
		return Objects.toString(m_programConfig, "ProgramCompanionConfig[programConfig=null]");
	}
}
