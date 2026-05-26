package mdt.endpoint.companion;

import java.util.List;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.model.ServiceSpecificationProfile;

import utils.async.command.ProgramService;
import utils.async.command.ServiceShutdownHook;
import utils.func.FOption;

import mdt.config.MDTInstanceConfig;
import mdt.config.MDTService;


/**
 * MDT instance에 부속되어 외부 프로그램(자식 프로세스)을 함께 기동·종료시키는 FA³ST {@link Endpoint} companion.
 * <p>
 * 이름은 {@code Endpoint}이지만 실제 통신 endpoint를 제공하지 않고,
 * {@link ProgramCompanionConfig#getProgramConfig()}에 기술된 외부 프로그램을
 * {@link ProgramService}로 감싸 비동기 실행한다.
 * FA³ST 서비스의 lifecycle({@link #init init}/{@link #start start}/{@link #stop stop})에 맞춰
 * 외부 프로그램을 함께 시작·중지시키는 것이 목적이며, 재시작 정책과 표준 출력/오류 처리는
 * 모두 {@link ProgramService}의 동작에 위임된다.
 * <p>
 * 또한 {@link #init init} 시점에 내부 {@link ProgramService}를 JVM 종료 hook
 * ({@link ServiceShutdownHook})에 등록하므로, FA³ST 서비스가 비정상 종료되더라도
 * 자식 프로세스가 함께 정리된다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanion implements Endpoint<ProgramCompanionConfig> {
	private ProgramCompanionConfig m_config;
	private ProgramService m_service;
	private ServiceShutdownHook.Registration m_hookReg;

	/**
	 * Companion을 초기화한다.
	 * <p>
	 * 설정의 {@link ProgramCompanionConfig#getProgramConfig() programConfig}로
	 * {@link ProgramService}를 생성하고, JVM 종료 시 외부 프로그램이 정리되도록
	 * {@link ServiceShutdownHook}에 등록한다.
	 * {@code serviceContext}가 {@link MDTService}인 경우 instance id를 prefix로 한
	 * 이름(예: {@code <instanceId>Companion})으로 등록되어 로그 추적을 쉽게 한다.
	 * <p>
	 * 본 메서드는 인스턴스당 정확히 한 번만 호출되어야 한다.
	 * Guava {@link com.google.common.util.concurrent.Service Service}는 한 번
	 * 종료되면 재시작할 수 없는 비가역적 lifecycle을 가지므로 재초기화는 의미가 없으며,
	 * 잘못된 사용을 조기에 드러내기 위해 재호출 시 {@link IllegalStateException}을 던진다.
	 *
	 * @param coreConfig     FA³ST 서비스의 코어 설정 (본 구현에서는 사용하지 않음).
	 * @param config         이 companion의 설정.
	 * @param serviceContext FA³ST 서비스 컨텍스트. {@link MDTService}이면 instance id를 이름에 반영한다.
	 * @throws IllegalStateException 이미 초기화된 상태에서 재호출된 경우.
	 */
	@Override
	public void init(CoreConfig coreConfig, ProgramCompanionConfig config, ServiceContext serviceContext) {
		if ( m_service != null ) {
			throw new IllegalStateException("ProgramCompanion is already initialized");
		}
		m_config = config;
		m_service = ProgramService.create(m_config.getProgramConfig());

		String companionName = "Companion";
		if ( serviceContext instanceof MDTService mdtCtxt ) {
			String instanceId = FOption.ofNullable(mdtCtxt.getInstanceConfig())
										.map(MDTInstanceConfig::getId)
										.getOrElse("");
			if ( !instanceId.isEmpty() ) {
				companionName = instanceId + companionName;
			}
		}
		m_hookReg = ServiceShutdownHook.register(companionName, m_service);
	}

	/**
	 * {@link #init init} 시점에 전달된 설정 객체를 그대로 반환한다.
	 *
	 * @return 이 companion의 {@link ProgramCompanionConfig}.
	 */
	@Override
	public ProgramCompanionConfig asConfig() {
		return m_config;
	}

	/**
	 * 이 companion이 지원하는 {@link ServiceSpecificationProfile} 목록을 반환한다.
	 * <p>
	 * 실제 값은 {@link ProgramCompanionConfig}에 설정된 것을 그대로 위임한다.
	 *
	 * @return 지원 프로파일 목록.
	 */
	@Override
	public List<ServiceSpecificationProfile> getProfiles() {
		return m_config.getProfiles();
	}

	/**
	 * 외부 프로그램을 비동기로 기동시킨다.
	 * <p>
	 * 내부적으로 {@link ProgramService#startAsync()}를 호출하므로 본 메서드는 즉시 반환하며,
	 * 자식 프로세스의 RUNNING 진입은 {@code ProgramService}에서 관리된다.
	 */
	@Override
	public void start() {
		m_service.startAsync();
	}

	/**
	 * 외부 프로그램을 비동기로 중지시킨다.
	 * <p>
	 * 내부적으로 {@link ProgramService#stopAsync()}를 호출하므로 본 메서드는 즉시 반환한다.
	 * 실제 자식 프로세스의 종료(SIGTERM 후 필요시 SIGKILL)는 {@code ProgramService}/
	 * {@code CommandExecution}의 종료 절차를 따른다.
	 * <p>
	 * {@link #init init}에서 등록한 JVM shutdown hook도 함께 해제하여,
	 * JVM 종료 시점에 이미 TERMINATED 상태인 service에 대해 중복으로 {@code stopAsync}가
	 * 호출되는 잡음을 방지한다. JVM 종료와 본 메서드 호출이 거의 동시에 발생하는 경우에도
	 * {@link ServiceShutdownHook.Registration#unregister()}가 race를 안전하게 처리한다.
	 */
	@Override
	public void stop() {
		m_service.stopAsync();
		if ( m_hookReg != null ) {
			m_hookReg.unregister();
			m_hookReg = null;
		}
	}
}