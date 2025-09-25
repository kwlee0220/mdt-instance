package mdt.config;

import lombok.experimental.Delegate;

import de.fraunhofer.iosb.ilt.faaast.service.config.ServiceConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTServiceConfig extends ServiceConfig {
	@Delegate private final ServiceConfig m_inner;
	private final MDTInstanceConfig m_instConfig;

	public MDTServiceConfig(ServiceConfig inner, MDTInstanceConfig instConfig) {
		m_inner = inner;
		m_instConfig = instConfig;
	}
	
	public MDTInstanceConfig getInstanceConfig() {
		return m_instConfig;
	}
}
