package mdt.persistence;

import java.io.File;

import com.fasterxml.jackson.annotation.JsonProperty;

import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.memory.PersistenceInMemoryConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class PersistenceStackConfig<P extends PersistenceStack> extends PersistenceConfig<P> {
	private PersistenceConfig<?> m_baseConfig;
	
	protected PersistenceStackConfig(PersistenceConfig<?> baseConfig) {
		m_baseConfig = baseConfig;
	}
	
	@JsonProperty("basePersistence")
	public PersistenceConfig<?> getBasePersistenceConfig() {
		return m_baseConfig;
	}

	@JsonProperty("basePersistence")
	public void setBasePersistenceConfig(PersistenceConfig<?> config) {
		if ( config instanceof PersistenceInMemoryConfig ) {
			((PersistenceInMemoryConfig) config).setInitialModelFile(new File("model.json"));
		}
		
		m_baseConfig = config;
	}
    
    @Override
	public String toString() {
		return String.format("%s: model=%s", getClass().getName(), getInitialModelFile());
	}
}
