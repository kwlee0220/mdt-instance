package mdt.endpoint;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractScheduledService;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.AbstractEndpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;

import utils.http.HttpRESTfulClient;


/**
 * MDTManager의 health를 주기적으로 모니터링하는 서비스.
 * <p>
 * MDTManager의 health를 주기적으로 체크하여, MDTManager와의 연결이 끊어지면
 * MDTManager가 종료된 것으로 간주하고, 자동으로 MDTInstance를 종료한다.
 * <p>
 * 이 기능을 통해 MDTManager가 비정상적으로 종료되었을 때, 동작 중인 모든 MDTInstance를
 * 종료시켜, 추후 MDTManager가 다시 시작되어 등록된 MDTInstance를 다시 시작시킬 때
 * 포트 충돌로 인한 MDTInstance 시작 실패를 방지한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTManagerHealthMonitor extends AbstractEndpoint<MDTManagerHealthMonitorConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTManagerHealthMonitor.class);

	private String m_url;
	private HttpRESTfulClient m_restfulClient;
	
	private final AbstractScheduledService m_schedule = new AbstractScheduledService() {
		@Override
		protected void runOneIteration() throws Exception {
			try {
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("check MDTManager health: url={}", m_url);
				}
				m_restfulClient.get(m_url);
			}
			catch ( Exception e ) {
				if ( s_logger.isInfoEnabled() ) {
					s_logger.info("Failed to connect MDTManager: {} -> shutting-down MDTInstance", this);
				}
				System.exit(0);
			}
		}

		@Override
		protected Scheduler scheduler() {
			long intervalMillis = asConfig().getCheckInterval().toMillis();
			return Scheduler.newFixedRateSchedule(0, intervalMillis, TimeUnit.MILLISECONDS);
		}
	};

	@Override
	public void init(CoreConfig coreConfig, MDTManagerHealthMonitorConfig config, ServiceContext serviceContext) {
		super.init(coreConfig, config, serviceContext);
		
		m_url = String.format("%s/health", config.getManagerEndpoint());
		m_restfulClient = HttpRESTfulClient.newDefaultClient();
	}
	
    @Override
    public void start() throws EndpointException {
    	if ( asConfig().isEnabled() ) {
    		if ( s_logger.isInfoEnabled() ) {
    			s_logger.info("starting MDTManagerHealthMonitor: {}", this);
			}
    		m_schedule.startAsync();
    	}
    }

    @Override
    public void stop() {
    	if ( asConfig().isEnabled() ) {
    		if ( s_logger.isInfoEnabled() ) {
    			s_logger.info("stopping MDTManagerHealthMonitor: {}", this);
			}
    		m_schedule.stopAsync();
    	}
    }
	
	@Override
	public String toString() {
		return String.format("%s[mdt-url=%s, checkInterval=%s]",
								getClass().getSimpleName(), m_url, asConfig().getCheckInterval());
	}
}
