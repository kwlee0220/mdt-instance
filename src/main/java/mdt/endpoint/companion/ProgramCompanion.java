package mdt.endpoint.companion;

import java.io.File;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.util.concurrent.AbstractService;

import utils.async.CommandExecution;
import utils.async.Guard;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanion extends AbstractService implements Endpoint<ProgramCompanionConfig> {
	private static final File APPLICATION_LOG = new File("application.log");
	
	private ProgramCompanionConfig m_config;
	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private CommandExecution m_exec;
	@GuardedBy("m_guard") private boolean m_stopRequested = false;

	@Override
	public void init(CoreConfig coreConfig, ProgramCompanionConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		m_config = config;
		
		m_exec = CommandExecution.builder()
								.addCommand(m_config.getCommand())
								.setWorkingDirectory(m_config.getWorkingDirectory())
								.setTimeout(null)
								.build();
	}

	@Override
	public ProgramCompanionConfig asConfig() {
		return m_config;
	}

	@Override
	public void start() throws EndpointException {
		doStart();
	}

	@Override
	public void stop() {
		doStop();
	}

	@Override
	protected void doStart() {
		m_guard.run(this::runCommand);
	}

	@Override
	protected void doStop() {
		m_guard.run(() -> {
			m_stopRequested = true;
			if ( m_exec != null ) {
				m_exec.cancel(true);
			}
			m_exec = null;
		});
	}
	
	private void runCommand() {
		m_stopRequested = false;
		m_exec = CommandExecution.builder()
								.addCommand(m_config.getCommand())
								.setWorkingDirectory(m_config.getWorkingDirectory())
								.setTimeout(null)
								.redirectStdoutToFile(APPLICATION_LOG)
								.redirectStderrToFile(APPLICATION_LOG)
								.build();
		m_exec.whenStartedAsync(() -> {
			notifyStarted();
		});
		m_exec.whenFinished(result -> {
			if ( m_stopRequested ) {
				notifyStopped();
				return;
			}
			
			switch ( m_config.getRestartPolicy() ) {
				case "always":
				case "unless-stopped":
				case "on-failure":
					runCommand();
					break;
				case "no":
					if ( result.isFailed() ) {
						notifyFailed(result.getCause());
					}
					else {
						notifyStopped();
					}
					break;
				default:
					throw new IllegalStateException("Unknown restart policy: " + m_config.getRestartPolicy());
			}
		});
		m_exec.start();
	}
}
