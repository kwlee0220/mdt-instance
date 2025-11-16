package mdt.endpoint.reconnector;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractScheduledService;

import utils.Throwables;
import utils.func.Unchecked;
import utils.http.HttpRESTfulClient;
import utils.http.JacksonErrorEntityDeserializer;
import utils.http.RESTfulIOException;

import mdt.model.MDTModelSerDe;
import mdt.model.instance.MDTInstanceManagerException;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import okhttp3.RequestBody;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTManagerReconnector extends AbstractScheduledService
										implements Endpoint<MDTManagerReconnectorConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTManagerReconnector.class);

	private MDTManagerReconnectorConfig m_config;
	private String m_registerUrl;
	private HttpRESTfulClient m_restfulClient;
	private Duration m_heartbeatInterval;
	private RequestBody m_reqBody;

	@Override
	public void init(CoreConfig coreConfig, MDTManagerReconnectorConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		m_config = config;

		m_registerUrl = String.format("%s/instance-manager/registry/%s", config.getMdtEndpoint(), config.getInstanceId());
		
		m_restfulClient = HttpRESTfulClient.builder()
											.errorEntityDeserializer(new JacksonErrorEntityDeserializer(MDTModelSerDe.MAPPER))
											.build();
		
		String repoEp = config.getRepositoryEndpoint();
		if ( repoEp == null ) {
			throw new ConfigurationInitializationException("'repositoryEndpoint' is missing "
															+ "in MDTManagerReconnectorConfig");
		}
		m_reqBody = RequestBody.create(repoEp, HttpRESTfulClient.MEDIA_TYPE_TEXT);

		m_heartbeatInterval = config.getHeartbeatInterval();
	}

	@Override
	public MDTManagerReconnectorConfig asConfig() {
		return m_config;
	}
	
    @Override
    public void start() throws EndpointException {
    	if ( m_config.isEnabled() ) {
			s_logger.info("Starting service: {}[{}]", getClass().getSimpleName(), this);
    		
    		startAsync();
    	}
    }

    @Override
    public void stop() {
    	if ( m_config.isEnabled() ) {
			s_logger.info("Stopping service: {}[{}]", getClass().getSimpleName(), this);
    		
    		stopAsync();
    		Unchecked.runOrIgnore(() -> m_restfulClient.delete(m_registerUrl));
    	}
    }

	@Override
	protected void runOneIteration() throws Exception {
		try {
			s_logger.debug("try to connect to MDTManager: url={}", m_registerUrl);

			m_restfulClient.post(m_registerUrl, m_reqBody, HttpRESTfulClient.STRING_DESER);
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
//					throw Throwables.toException(cause);
				}
			}
		}
	}

	@Override
	protected Scheduler scheduler() {
		return Scheduler.newFixedRateSchedule(0, m_heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
	}
	
	@Override
	public String toString() {
		return String.format("mdt-url=%s, poll-interval=%s", m_registerUrl, m_heartbeatInterval);
	}
}
