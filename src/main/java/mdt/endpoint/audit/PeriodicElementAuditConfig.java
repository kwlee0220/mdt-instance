package mdt.endpoint.audit;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.UnitUtils;
import utils.jdbc.JdbcConfiguration;
import utils.stream.FStream;

import mdt.ElementColumnConfig;
import mdt.MDTGlobalConfigurations;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


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
	@Getter @Setter
	@Accessors(prefix="m_")
	public static class MDTConfig {
		private String m_table;
		private Object m_jdbcConfig = "default";
		private String m_timestampColumn = "timestamp";
		private List<ElementColumnConfig> m_columns;
		private String m_interval;
		private boolean m_enabled = true;
		
		@Override
		public String toString() {
			String colsStr = FStream.from(this.m_columns)
									.map(ElementColumnConfig::getName)
									.join(", ");
			return String.format("%s{%s}, interval=%s", m_table, colsStr, m_interval);
		}
	}
}
