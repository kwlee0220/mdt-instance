package mdt.endpoint.companion;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import utils.Preconditions;
import utils.async.command.ProgramServiceConfig;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 * {@link ProgramCompanion}의 FA³ST endpoint 설정.
 * <p>
 * MDT 인스턴스 설정의 {@code serviceEndpoints.companion}/{@code companions} 항목({@link CompanionConfig})으로부터
 * 로더가 {@link #from(String, CompanionConfig, Map)}로 생성한다. 동반 프로그램 하나당 하나씩 만들어진다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonPropertyOrder({ "name", "programConfig", "required" })
public class ProgramCompanionConfig extends EndpointConfig<ProgramCompanion> {
	// hook 쓰레드 이름과 logger 이름에 쓰이므로 '.'이나 공백이 들어가지 않게 한다.
	private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

	@Nullable private String m_name;
	private ProgramServiceConfig m_programConfig;
	private boolean m_required = false;

	/**
	 * 인스턴스 설정의 동반 프로그램 항목으로 companion 설정을 만든다.
	 * <p>
	 * 인스턴스 변수({@code MDT_INSTANCE_ID} 등)에 동반 프로그램의 {@code environments}를
	 * {@link ProgramCompanion#mergeEnvironments(Map, Map) 병합}하여 {@code companionConfig}에 다시 설정한다.
	 *
	 * @param name				동반 프로그램 이름. {@code companions}의 키이며, 단일 {@code companion}이면 {@code null}.
	 * @param companionConfig	동반 프로그램 실행 설정.
	 * @param instanceVars		MDT 인스턴스 변수.
	 * @return	생성된 companion 설정.
	 * @throws IllegalArgumentException	{@code name}이 이름 규칙에 맞지 않는 경우.
	 */
	public static ProgramCompanionConfig from(@Nullable String name, CompanionConfig companionConfig,
												Map<String,String> instanceVars) {
		Preconditions.checkNotNullArgument(companionConfig, "companionConfig is null");

		companionConfig.setEnvironments(ProgramCompanion.mergeEnvironments(instanceVars,
																			companionConfig.getEnvironments()));

		ProgramCompanionConfig config = new ProgramCompanionConfig();
		config.setName(name);
		config.setProgramConfig(companionConfig);
		config.setRequired(companionConfig.isRequired());
		return config;
	}

	/**
	 * 동반 프로그램 이름을 반환한다. hook 이름과 로그에 쓰인다.
	 *
	 * @return	이름. 단일 {@code companion} 항목이면 {@code null}.
	 */
	public @Nullable String getName() {
		return m_name;
	}

	/**
	 * 동반 프로그램 이름을 설정한다.
	 *
	 * @param name	영문자·숫자로 시작하고 영문자·숫자·{@code _}·{@code -}로 이루어진 이름. {@code null}이면 이름 없음.
	 * @throws IllegalArgumentException	이름 규칙에 맞지 않는 경우.
	 */
	public void setName(@Nullable String name) {
		Preconditions.checkArgument(name == null || NAME_PATTERN.matcher(name).matches(),
									"invalid companion name: '%s' (allowed: letters, digits, '_', '-')", name);
		m_name = name;
	}

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
		String prefix = (m_name != null) ? m_name + ": " : "";
		return prefix + Objects.toString(m_programConfig, "ProgramCompanionConfig[programConfig=null]");
	}
}
