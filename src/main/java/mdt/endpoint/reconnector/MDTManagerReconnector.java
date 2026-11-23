package mdt.endpoint.reconnector;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractScheduledService;

import okhttp3.RequestBody;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.AbstractEndpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;

import utils.Throwables;
import utils.func.Unchecked;
import utils.http.HttpRESTfulClient;
import utils.http.JacksonErrorEntityDeserializer;
import utils.http.RESTfulIOException;

import mdt.model.MDTModelSerDe;
import mdt.model.instance.MDTInstanceManagerException;


/**
 * 외부(EXTERNAL) 인스턴스를 MDT Manager에 주기적으로 등록하여 연결을 유지하는 endpoint.
 * <p>
 * {@code POST {mdtUrl}/instance-manager/registry/{id}}의 본문으로 저장소 주소(평문)를 보낸다. 취소 엔드포인트
 * 주소가 설정되어 있으면 쿼리 파라미터 {@code operationControlEndpoint}로 함께 보낸다. 본문 형식은 바꾸지
 * 않으므로, 이 파라미터를 모르는 MDT Manager에도 그대로 등록된다. 중지할 때는 같은 경로로 {@code DELETE}를 보낸다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTManagerReconnector extends AbstractEndpoint<MDTManagerReconnectorConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTManagerReconnector.class);

	private String m_registerUrl;
	private String m_registerPostUrl;
	private HttpRESTfulClient m_restfulClient;
	private Duration m_heartbeatInterval;
	private RequestBody m_reqBody;

	@Override
	public void init(CoreConfig coreConfig, MDTManagerReconnectorConfig config, ServiceContext serviceContext) {
		super.init(coreConfig, config, serviceContext);

		m_registerUrl = String.format("%s/instance-manager/registry/%s", config.getMdtUrl(), config.getInstanceId());
		m_registerPostUrl = toRegisterPostUrl(m_registerUrl, config.getOperationControlEndpoint());
		
		m_restfulClient = HttpRESTfulClient.builder()
											.errorEntityDeserializer(new JacksonErrorEntityDeserializer(MDTModelSerDe.MAPPER))
											.build();
		
		String repoEp = config.getRepositoryEndpoint();
		if ( repoEp == null ) {
			throw new IllegalArgumentException("'repositoryEndpoint' is missing in MDTManagerReconnectorConfig");
		}
		m_reqBody = RequestBody.create(repoEp, HttpRESTfulClient.MEDIA_TYPE_TEXT);

		m_heartbeatInterval = config.getHeartbeatInterval();
	}
	
    @Override
    public void start() throws EndpointException {
    	if ( asConfig().isEnabled() ) {
			s_logger.info("Starting service: {}[{}]", getClass().getSimpleName(), this);
    		
    		m_schedule.startAsync();
    	}
    }

    @Override
    public void stop() {
    	if ( asConfig().isEnabled() ) {
			s_logger.info("Stopping service: {}[{}]", getClass().getSimpleName(), this);
    		
    		m_schedule.stopAsync();
    		Unchecked.runOrIgnore(() -> m_restfulClient.delete(m_registerUrl));
    	}
    }
	
	/**
	 * 등록 요청(POST)의 URL을 만든다. 취소 엔드포인트 주소가 있으면 쿼리 파라미터로 붙인다.
	 *
	 * @param registerUrl				등록 경로 URL.
	 * @param operationControlEndpoint	취소 엔드포인트 주소. {@code null}이면 붙이지 않는다.
	 * @return	등록 요청 URL.
	 */
	static String toRegisterPostUrl(String registerUrl, String operationControlEndpoint) {
		if ( operationControlEndpoint == null ) {
			return registerUrl;
		}
		return registerUrl + "?operationControlEndpoint="
				+ URLEncoder.encode(operationControlEndpoint, StandardCharsets.UTF_8);
	}
	
	@Override
	public String toString() {
		return String.format("mdt-url=%s, poll-interval=%s", m_registerUrl, m_heartbeatInterval);
	}
	
	private final AbstractScheduledService m_schedule = new AbstractScheduledService() {
		@Override
		protected void runOneIteration() throws Exception {
			try {
				s_logger.debug("try to connect to MDTManager: url={}", m_registerUrl);

				m_restfulClient.post(m_registerPostUrl, m_reqBody, HttpRESTfulClient.STRING_DESER);
			}
			catch ( Throwable e ) {
				if ( s_logger.isInfoEnabled() ) {
					if ( e instanceof RESTfulIOException rio ) {
						e = rio.getCause();
					}
					Throwable cause = Throwables.unwrapThrowable(e);
					s_logger.error("Failed to connect MDTManager: url={}, cause={}",
									m_registerUrl, ""+cause);
					
					if ( cause instanceof MDTInstanceManagerException ) {
						System.exit(-1);
//						throw Throwables.toException(cause);
					}
				}
			}
		}

		@Override
		protected Scheduler scheduler() {
			return Scheduler.newFixedRateSchedule(0, m_heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
		}
	};
}
