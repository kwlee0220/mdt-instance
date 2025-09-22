package mdt.persistence;

import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ConcurrentPersistenceConfig extends PersistenceStackConfig<ConcurrentPersistence> {
	private final Core m_config;
	
	public ConcurrentPersistenceConfig(Core config, PersistenceConfig<?> baseConfig) {
		super(baseConfig);
		
		m_config = config;
	}
	
	@Override
	public String toString() {
		return String.format("%s: %s", getClass().getSimpleName(), super.toString());
	}

	@Override
	public boolean equals(Object obj) {
		if ( this == obj ) {
			return true;
		}
		if ( obj == null || !(obj instanceof ConcurrentPersistenceConfig) ) {
			return false;
		}

		return super.equals(obj);
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}

	public static class Core implements MDTPersistenceStackConfig { }
}
