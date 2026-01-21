package mdt.endpoint.companion;

import java.util.List;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;
import de.fraunhofer.iosb.ilt.faaast.service.model.ServiceSpecificationProfile;

import utils.async.command.ProgramService;
import utils.async.command.ServiceShutdownHook;

import mdt.config.MDTService;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ProgramCompanion implements Endpoint<ProgramCompanionConfig> {
	private ProgramCompanionConfig m_config;
	private ProgramService m_service;

	@Override
	public void init(CoreConfig coreConfig, ProgramCompanionConfig config, ServiceContext serviceContext) {
		m_config = config;
		m_service = ProgramService.create(m_config.getProgramConfig());

		String companionName = "Companion";
		if ( serviceContext instanceof MDTService mdtCtxt ) {
			companionName = mdtCtxt.getInstanceConfig().getId() + companionName;	
		}
		ServiceShutdownHook.register(companionName, m_service);
	}

	@Override
	public ProgramCompanionConfig asConfig() {
		return m_config;
	}

	@Override
	public List<ServiceSpecificationProfile> getProfiles() {
		return m_config.getProfiles();
	}

	@Override
	public void start() throws EndpointException {
		m_service.startAsync();
	}

	@Override
	public void stop() {
		m_service.stopAsync();
	}
}