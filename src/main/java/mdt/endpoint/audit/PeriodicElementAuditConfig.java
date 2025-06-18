package mdt.endpoint.audit;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import utils.UnitUtils;
import utils.stream.FStream;

import mdt.ElementColumnConfig;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({"jdbcConfig", "table", "timestampColumn", "columns", "interval", "distinct", "enabled"})
@JsonInclude(Include.NON_NULL)
public class PeriodicElementAuditConfig extends EndpointConfig<PeriodicElementAudit> {
	private String m_table;
	private String m_jdbcConfig = "default";
	private String m_timestampColumn = "timestamp";
	private List<ElementColumnConfig> m_columns;
	private Duration m_interval;
	private boolean m_distinct = true;
	private boolean m_enabled = true;

	@JsonProperty("table")
	public String getTable() {
		return m_table;
	}
	
	@JsonProperty("table")
	public void setTable(String table) {
		m_table = table;
	}
	
	@JsonProperty("jdbcConfig")
	public String getJdbcConfig() {
		return m_jdbcConfig;
	}
	
	@JsonProperty("jdbcConfig")
	public void setJdbcConfig(String config) {
		m_jdbcConfig = config;
	}
	
	@JsonProperty("timestampColumn")
	public String getTimestampColumn() {
		return m_timestampColumn;
	}

	@JsonProperty("timestampColumn")
	public void setTimestampColumn(String colName) {
		m_timestampColumn = colName;
	}
	
	@JsonProperty("columns")
	public List<ElementColumnConfig> getColumns() {
		return m_columns;
	}
	
	@JsonProperty("columns")
	public void setColumns(List<ElementColumnConfig> columns) {
		m_columns = columns;
	}
	
	public Duration getInterval() {
		return m_interval;
	}
	
	@JsonProperty("interval")
	public String getIntervalString() {
		return m_interval.toString();
	}
	
	@JsonProperty("interval")
	public void setIntervalString(String intvlStr) {
		this.m_interval = UnitUtils.parseDuration(intvlStr);
	}

	@JsonProperty("distinct")
	public boolean isDistinct() {
		return m_distinct;
	}

	@JsonProperty("distinct")
	public void setDistinct(boolean flag) {
		m_distinct = flag;
	}
	
	@JsonProperty("enabled")
	public boolean isEnabled() {
		return m_enabled;
	}
	
	@JsonProperty("enabled")
	public void setEnabled(boolean flag) {
		m_enabled = flag;
	}
	
	@Override
	public String toString() {
		String colsStr = FStream.from(this.m_columns)
								.map(ElementColumnConfig::getName)
								.join(", ");
		return String.format("%s{%s}, interval=%s, distinct=%s", m_table, colsStr, m_interval, m_distinct);
	}
}
