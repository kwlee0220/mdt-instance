package mdt.endpoint.companion;

import utils.async.command.ProgramServiceConfig;


/**
 * MDT 인스턴스 설정의 {@code serviceEndpoints.companion} 항목.
 * <p>
 * 동반 프로그램의 실행 설정({@link ProgramServiceConfig})에 companion 고유 설정을 더한다.
 * <ul>
 *   <li>{@code required} (기본값 {@code false}): {@code true}이면 동반 프로그램이 시작(RUNNING)되지 못할 때
 *       {@link ProgramCompanion#start()}가 실패하여 FA³ST 서비스 시작도 실패한다. 시작 이후의 실패는
 *       {@code required}와 관계없이 ERROR 로그만 남긴다.</li>
 * </ul>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class CompanionConfig extends ProgramServiceConfig {
	private boolean m_required = false;

	/**
	 * 동반 프로그램이 인스턴스 시작에 반드시 필요한지 여부를 반환한다.
	 *
	 * @return	필수이면 {@code true}.
	 */
	public boolean isRequired() {
		return m_required;
	}

	/**
	 * 동반 프로그램이 인스턴스 시작에 반드시 필요한지 여부를 설정한다.
	 *
	 * @param required	필수이면 {@code true}.
	 */
	public void setRequired(boolean required) {
		m_required = required;
	}

	@Override
	public String toString() {
		return super.toString() + (m_required ? ", required" : "");
	}
}
