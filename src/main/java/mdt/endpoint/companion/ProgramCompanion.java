package mdt.endpoint.companion;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.Service.State;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.model.ServiceSpecificationProfile;

import utils.Preconditions;
import utils.async.command.ProgramService;
import utils.async.command.ServiceShutdownHook;
import utils.func.FOption;

import mdt.config.MDTInstanceConfig;
import mdt.config.MDTService;


/**
 * MDT instance에 부속되어 외부 프로그램(자식 프로세스)을 함께 기동·종료시키는
 * FA³ST {@link Endpoint} companion.
 * <p>
 * 이름은 {@code Endpoint}이지만 실제 통신 endpoint를 제공하지 않고,
 * {@link ProgramCompanionConfig#getProgramConfig()}에 기술된 외부 프로그램을
 * {@link ProgramService}로 감싸 비동기 실행한다.
 * FA³ST 서비스의 lifecycle({@link #init init}/{@link #start start}/{@link #stop stop})에 맞춰
 * 외부 프로그램을 함께 시작·중지시키는 것이 목적이며, 재시작 정책과 표준 출력/오류 처리는
 * 모두 {@link ProgramService}의 동작에 위임된다.
 * <p>
 * 또한 {@link #init init} 시점에 내부 {@link ProgramService}를 JVM 종료 hook
 * ({@link ServiceShutdownHook})에 등록하므로, {@link #stop stop}이 호출되지 않은 채 JVM이 종료되더라도
 * 자식 프로세스가 함께 정리된다. 단, 이는 JVM이 종료 hook을 실행하는 경우(정상 종료, {@code System.exit},
 * SIGTERM·SIGINT 등)에 한한다. {@code kill -9}나 JVM 충돌처럼 hook이 실행되지 않으면 자식 프로세스는
 * 정리되지 않고 남으며, 프로세스 그룹 단위로 함께 종료시키는 장치도 없다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanion implements Endpoint<ProgramCompanionConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(ProgramCompanion.class);
	// 필수 동반 프로그램의 시작을 기다릴 때 startTimeout에 더하는 여유 시간.
	// ProgramService가 startTimeout으로 먼저 실패를 판정하도록(원인이 TimeoutException으로 남도록) 조금 더 기다린다.
	private static final Duration START_WAIT_MARGIN = Duration.ofSeconds(1);

	private ProgramCompanionConfig m_config;
	private String m_name;
	private ProgramService m_service;
	private ServiceShutdownHook.Registration m_hookReg;
	// stop()과 JVM 종료 hook이 프로그램 종료를 기다리는 시간. 두 경로가 같은 시간을 쓰도록 한 곳에서 정한다.
	private Duration m_terminationTimeout = ServiceShutdownHook.DEFAULT_TERMINATION_TIMEOUT;

	/**
	 * 동반 프로그램에 전달할 환경 변수를 만든다.
	 * <p>
	 * MDT 인스턴스 변수({@code MDT_INSTANCE_ID}, {@code MDT_URL} 등)를 기본으로 하고, 사용자가 설정의
	 * {@code environments}에 적은 변수를 그 위에 더한다. 같은 이름이 있으면 사용자 값이 우선하며,
	 * 이때 인스턴스 변수를 덮어썼다는 경고 로그를 남긴다. 값에는 비밀 정보가 있을 수 있으므로 로그에는
	 * 변수 이름만 출력한다. 두 인자는 변경하지 않는다.
	 *
	 * @param instanceVars	MDT 인스턴스 변수.
	 * @param userVars		사용자가 설정한 환경 변수. {@code null}이면 없는 것으로 본다.
	 * @return	병합한 새 환경 변수 맵.
	 */
	public static Map<String,String> mergeEnvironments(Map<String,String> instanceVars,
														@Nullable Map<String,String> userVars) {
		Preconditions.checkNotNullArgument(instanceVars, "instanceVars is null");

		Map<String,String> merged = new LinkedHashMap<>(instanceVars);
		if ( userVars != null ) {
			userVars.forEach((name, value) -> {
				String previous = merged.put(name, value);
				if ( previous != null && !previous.equals(value) ) {
					s_logger.warn("companion environment '{}' overrides the MDT instance variable", name);
				}
			});
		}
		return merged;
	}

	/**
	 * Companion을 초기화한다.
	 * <p>
	 * 설정의 {@link ProgramCompanionConfig#getProgramConfig() programConfig}로
	 * {@link ProgramService}를 생성하고, JVM 종료 시 외부 프로그램이 정리되도록
	 * {@link ServiceShutdownHook}에 등록한다.
	 * Hook 이름은 {@link #toHookName(String)}이 정하며, {@code serviceContext}가 {@link MDTService}이면
	 * instance id를 앞에 붙여(예: {@code robot-01-companion}) 로그 추적을 쉽게 한다.
	 * <p>
	 * 같은 이름으로 다음 두 가지도 설정하여, 어느 인스턴스의 동반 프로그램인지 로그에서 바로 알 수 있게 한다.
	 * <ul>
	 *   <li>{@link ProgramService}의 logger를 {@code mdt.endpoint.companion.ProgramCompanion.<hook 이름>}으로
	 *       바꾼다. 이 companion의 logger 아래에 놓이므로 companion의 로그 수준 설정을 함께 따른다.</li>
	 *   <li>{@link ProgramService}가 FAILED가 되면(시작 실패, 재시작 한도 초과 등) companion logger로
	 *       ERROR 로그를 남긴다. 인스턴스는 계속 실행된다.</li>
	 * </ul>
	 * <p>
	 * 본 메서드는 인스턴스당 정확히 한 번만 호출되어야 한다.
	 * Guava {@link com.google.common.util.concurrent.Service Service}는 한 번
	 * 종료되면 재시작할 수 없는 비가역적 lifecycle을 가지므로 재초기화는 의미가 없으며,
	 * 잘못된 사용을 조기에 드러내기 위해 재호출 시 {@link IllegalStateException}을 던진다.
	 *
	 * @param coreConfig     FA³ST 서비스의 코어 설정 (본 구현에서는 사용하지 않음).
	 * @param config         이 companion의 설정.
	 * @param serviceContext FA³ST 서비스 컨텍스트. {@link MDTService}이면 instance id를 이름에 반영한다.
	 * @throws IllegalArgumentException {@code config}가 null이거나 {@code programConfig}가 설정되지 않은 경우.
	 * @throws IllegalStateException 이미 초기화된 상태에서 재호출된 경우.
	 */
	@Override
	public void init(CoreConfig coreConfig, ProgramCompanionConfig config, ServiceContext serviceContext) {
		if ( m_service != null ) {
			throw new IllegalStateException("ProgramCompanion is already initialized");
		}
		Preconditions.checkNotNullArgument(config, "ProgramCompanionConfig is null");
		Preconditions.checkArgument(config.getProgramConfig() != null,
									"companion programConfig is not set: the program to run is unknown");
		
		m_config = config;
		m_service = ProgramService.create(m_config.getProgramConfig());

		String instanceId = null;
		if ( serviceContext instanceof MDTService mdtCtxt ) {
			instanceId = FOption.ofNullable(mdtCtxt.getInstanceConfig())
								.map(MDTInstanceConfig::getId)
								.getOrNull();
		}
		String name = toHookName(instanceId);
		m_name = name;
		
		m_service.setLogger(LoggerFactory.getLogger(ProgramCompanion.class.getName() + "." + name));
		m_service.addListener(new Service.Listener() {
			@Override
			public void failed(State from, Throwable failure) {
				s_logger.error("companion program failed: name={}, state={}, cause={}, program={}",
								name, from, ""+failure, m_config.getProgramConfig().getCommandLine());
			}
		}, MoreExecutors.directExecutor());
		
		m_hookReg = ServiceShutdownHook.register(name, m_service, m_terminationTimeout);
	}
	
	/**
	 * JVM 종료 hook의 이름을 만든다.
	 * <p>
	 * instance id가 있으면 {@code <instanceId>-companion}, 없으면 {@code companion}이다.
	 * 실제 hook 쓰레드 이름은 여기에 {@code -ShutdownHook}이 붙는다(예: {@code robot-01-companion-ShutdownHook}).
	 *
	 * @param instanceId	MDT 인스턴스 id. {@code null}이거나 빈 문자열이면 없는 것으로 본다.
	 * @return	hook 이름.
	 */
	static String toHookName(@Nullable String instanceId) {
		return (instanceId == null || instanceId.isEmpty()) ? "companion" : instanceId + "-companion";
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
	 * 외부 프로그램을 기동시킨다.
	 * <p>
	 * 내부적으로 {@link ProgramService#startAsync()}를 호출한다.
	 * {@link ProgramCompanionConfig#isRequired() required}가 {@code false}(기본값)이면 즉시 반환하며,
	 * 자식 프로세스의 RUNNING 진입은 {@code ProgramService}에서 관리된다.
	 * <p>
	 * {@code required}가 {@code true}이면 프로그램이 RUNNING(자식 프로세스 생성)이 될 때까지
	 * {@code startTimeout}(0이면 제한 없이)만큼 기다린다. 그 안에 시작되지 못하면(실행 파일이나 작업 디렉터리가
	 * 없거나, {@code startTimeout}이 지난 경우 등) {@link EndpointException}을 던져 FA³ST 서비스의 시작을
	 * 실패시킨다. RUNNING은 프로세스가 생성되었다는 뜻일 뿐 프로그램이 업무를 처리할 준비가 되었다는 뜻은
	 * 아니며, 시작 이후의 실패에는 {@code required}가 영향을 주지 않는다(ERROR 로그만 남는다).
	 *
	 * @throws EndpointException	{@code required}인 동반 프로그램이 시작되지 못한 경우.
	 */
	@Override
	public void start() throws EndpointException {
		m_service.startAsync();
		if ( m_config.isRequired() ) {
			awaitRequiredStart();
		}
	}
	
	private void awaitRequiredStart() throws EndpointException {
		Duration startTimeout = m_config.getProgramConfig().getStartTimeout();
		try {
			if ( startTimeout.isZero() ) {
				m_service.awaitRunning();
			}
			else {
				m_service.awaitRunning(startTimeout.plus(START_WAIT_MARGIN).toNanos(), TimeUnit.NANOSECONDS);
			}
		}
		catch ( IllegalStateException e ) {
			// RUNNING에 이르지 못하고 FAILED(또는 TERMINATED)가 된 경우.
			Throwable cause = (m_service.state() == State.FAILED) ? m_service.failureCause() : e;
			throw new EndpointException("required companion program failed to start: name=" + m_name
										+ ", cause=" + cause, cause);
		}
		catch ( TimeoutException e ) {
			// ProgramService가 startTimeout으로 실패시키기 전에 여유 시간까지 지난 경우. 시작을 포기시킨다.
			m_service.stopAsync();
			throw new EndpointException("required companion program did not start within " + startTimeout
										+ ": name=" + m_name, e);
		}
	}

	/**
	 * 외부 프로그램을 중지시키고, 종료될 때까지 최대 {@link ServiceShutdownHook#DEFAULT_TERMINATION_TIMEOUT}
	 * (3초) 기다린다.
	 * <p>
	 * 자식 프로세스의 종료(SIGTERM 후 필요시 SIGKILL)는 {@code ProgramService}/{@code CommandExecution}의
	 * 종료 절차를 따른다. 대기 시간은 {@link #init init}에서 등록한 JVM 종료 hook과 같다.
	 * <p>
	 * 프로그램이 종료되면(TERMINATED 또는 FAILED) JVM 종료 hook을 해제하여, JVM 종료 시점에 이미 끝난
	 * service에 대해 중복으로 {@code stopAsync}가 호출되는 잡음을 방지한다. 대기 시간 안에 종료되지 않으면
	 * 경고 로그를 남기고 hook을 그대로 두어, JVM 종료 시 hook이 한 번 더 정리를 기다리게 한다.
	 * JVM 종료와 본 메서드 호출이 거의 동시에 발생하는 경우에도
	 * {@link ServiceShutdownHook.Registration#unregister()}가 race를 안전하게 처리한다.
	 */
	@Override
	public void stop() {
		m_service.stopAsync();
		try {
			m_service.awaitTerminated(m_terminationTimeout.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch ( TimeoutException e ) {
			s_logger.warn("companion program did not terminate within {}, keeping the shutdown hook: {}",
							m_terminationTimeout, m_service);
			return;
		}
		catch ( IllegalStateException e ) {
			// 서비스가 FAILED로 끝난 경우. 이미 종료된 상태이므로 hook을 해제해도 된다.
		}
		
		if ( m_hookReg != null ) {
			m_hookReg.unregister();
			m_hookReg = null;
		}
	}
	
	// 아래는 테스트용 package-private 메서드이다.
	
	void setTerminationTimeout(Duration timeout) {
		Preconditions.checkNotNullArgument(timeout, "timeout is null");
		m_terminationTimeout = timeout;
	}
	
	Service.State getProgramState() {
		return m_service.state();
	}
	
	boolean isShutdownHookRegistered() {
		return m_hookReg != null;
	}
}