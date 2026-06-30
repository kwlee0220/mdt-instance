package mdt.endpoint.audit;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;

import utils.UnitUtils;
import utils.jdbc.JdbcConfiguration;
import utils.stream.FStream;

import mdt.ElementColumnConfig;
import mdt.MDTGlobalConfigurations;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({ "table", "jdbcConfig", "timestampColumn", "columns", "interval", "enabled" })
public class PeriodicElementAuditConfig extends EndpointConfig<PeriodicElementAudit> {
	private final MDTConfig m_conf;
	
	public PeriodicElementAuditConfig(MDTConfig conf) {
		m_conf = conf;
	}

	public String getTable() {
		return m_conf.getTable();
	}
	
	public JdbcConfiguration getJdbcConfig() {
		if ( m_conf.getJdbcConfig() instanceof JdbcConfiguration jdbcConf ) {
			return jdbcConf;
		}
		else if ( m_conf.getJdbcConfig() instanceof String configName ) {
			return MDTGlobalConfigurations.getJdbcConfig(configName);
		}
		else {
			throw new IllegalArgumentException("unsupported jdbcConfig: " + m_conf.getJdbcConfig());
		}
	}
	
	public String getTimestampColumn() {
		return m_conf.getTimestampColumn();
	}
	
	public List<ElementColumnConfig> getColumns() {
		return m_conf.getColumns();
	}
	
	public String getInterval() {
		return m_conf.getInterval();
	}
	public Duration getIntervalDuration() {
		return UnitUtils.parseDuration(m_conf.getInterval());
	}
	
	public boolean isEnabled() {
		return m_conf.isEnabled();
	}
	
	@Override
	public String toString() {
		return m_conf.toString();
	}

	@JsonIncludeProperties({ "table", "jdbcConfig", "timestampColumn", "columns", "interval", "enabled" })
	public static class MDTConfig {
		private String m_table;
		private Object m_jdbcConfig = "default";
		private String m_timestampColumn = "timestamp";
		private List<ElementColumnConfig> m_columns;
		private String m_interval;
		private boolean m_enabled = true;
		
		public String getTable() {
			return m_table;
		}
		
		void setTable(String table) {
			m_table = table;
		}
		
		public Object getJdbcConfig() {
			return m_jdbcConfig;
		}
		
		void setJdbcConfig(Object jdbcConfig) {
			m_jdbcConfig = jdbcConfig;
		}
		
		public String getTimestampColumn() {
			return m_timestampColumn;
		}
		
		void setTimestampColumn(String timestampColumn) {
			m_timestampColumn = timestampColumn;
		}
		
		public List<ElementColumnConfig> getColumns() {
			return m_columns;
		}
		
		void setColumns(List<ElementColumnConfig> columns) {
			m_columns = columns;
		}
		
		public String getInterval() {
			return m_interval;
		}
		
		void setInterval(String interval) {
			m_interval = interval;
		}
		
		public boolean isEnabled() {
			return m_enabled;
		}
		
		void setEnabled(boolean enabled) {
			m_enabled = enabled;
		}
		
		@Override
		public String toString() {
			String colsStr = FStream.from(this.m_columns)
									.map(ElementColumnConfig::getName)
									.join(", ");
			return String.format("%s{%s}, interval=%s", m_table, colsStr, m_interval);
		}
	}
}
