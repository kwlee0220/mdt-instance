package mdt.persistence.timeseries;

import java.util.List;

import mdt.persistence.MDTPersistenceStackConfig;
import mdt.persistence.PersistenceStackConfig;

import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TimeSeriesPersistenceStackConfig extends PersistenceStackConfig<TimeSeriesPersistenceStack> {
	private final Core m_config;
	
	public TimeSeriesPersistenceStackConfig(Core config, PersistenceConfig<?> baseConfig) {
		super(baseConfig);
		
		m_config = config;
	}

	public List<TimeSeriesSubmodelConfig> getTimeSeriesSubmodelConfigs() {
		return m_config.getTimeSeriesSubmodels();
	}

	public static class Core implements MDTPersistenceStackConfig {
		private List<TimeSeriesSubmodelConfig> m_tsSubmodelConfigList = List.of();
	
		public List<TimeSeriesSubmodelConfig> getTimeSeriesSubmodels() {
			return m_tsSubmodelConfigList;
		}
		
		public void setTimeSeriesSubmodels(List<TimeSeriesSubmodelConfig> tsConfigList) {
			m_tsSubmodelConfigList = tsConfigList;
		}
	}
}