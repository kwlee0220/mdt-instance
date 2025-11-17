package mdt.endpoint.audit;

import java.time.Instant;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.Endpoint;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.exception.EndpointException;

import utils.async.PeriodicLoopExecution;
import utils.func.FOption;
import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;
import utils.jdbc.JdbcUtils;
import utils.stream.FStream;

import mdt.ElementColumnConfig;
import mdt.ElementLocation;
import mdt.FaaastRuntime;
import mdt.aas.DataType;
import mdt.aas.DataTypes;
import mdt.model.MDTModelSerDe;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.MDTModelLookup;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class PeriodicElementAudit implements Endpoint<PeriodicElementAuditConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(PeriodicElementAudit.class);

	private JdbcProcessor m_jdbc;
	private FaaastRuntime m_faaast;
	private PeriodicElementAuditConfig m_config;
	private List<ElementColumnConfig> m_columns;
	private PeriodicLoopExecution<Void> m_periodicAudit;
	private List<Object> m_lastValues;
	private String m_insertSql;

	@Override
	public void init(CoreConfig coreConfig, PeriodicElementAuditConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		m_config = config;
		if ( !m_config.isEnabled() ) {
			return;
		}
		
		m_faaast = new FaaastRuntime(serviceContext);
		m_columns = config.getColumns();
		
		String colCsv = FStream.from(m_columns).map(ElementColumnConfig::getName).join(',');
		String paramCsv = FStream.range(0, m_columns.size()).map(idx -> "?").join(',');
		m_lastValues = FStream.from(m_columns).map(c -> new Object()).toList();
		m_insertSql = String.format("insert into %s(%s,%s) values (?, %s)",
									config.getTable(), config.getTimestampColumn(), colCsv, paramCsv);
		
		JdbcConfiguration jdbcConfig = m_config.getJdbcConfig();
		m_jdbc = JdbcProcessor.create(jdbcConfig);
	}

	@Override
	public PeriodicElementAuditConfig asConfig() {
		return m_config;
	}
	
    @Override
    public void start() throws EndpointException {
    	if ( !m_config.isEnabled() ) {
    		return;
    	}
    	
    	m_periodicAudit = new PeriodicLoopExecution<Void>(m_config.getIntervalDuration()) {
			@Override
			protected FOption<Void> performPeriodicAction(long loopIndex) throws Exception {
				audit(loopIndex);
				return FOption.empty();
			}
		};
		m_periodicAudit.setLogger(s_logger);
		
		final MDTModelLookup lookup = MDTModelLookup.getInstance();
		FStream.from(m_config.getColumns())
				.forEach(col -> col.getElementLocation().activate(lookup));
		
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("Starting {}, config={}", getClass().getSimpleName(), m_config);
		}
		m_periodicAudit.start();
    }

    @Override
    public void stop() {
    	if ( !m_config.isEnabled() ) {
    		return;
    	}
    	m_periodicAudit.cancel(true);
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("Stopped {}, config={}", getClass().getSimpleName(), m_config);
		}
    }

	private void audit(long loopIndex) throws Exception {
		// Audit 대상의 element 위치를 이용해서 현재 SubmodelElement 값을 수집하고, Jdbc 객체로 변환한다.
		List<Object> values = FStream.from(m_columns)
									.map(col -> toJdbcObject(col.getElementLocation()))
									.toList();
		if ( m_lastValues.equals(values) ) {
			return;
		}
		
		m_jdbc.executeUpdate(m_insertSql, pstmt -> {
			FStream.from(values)
					.zipWithIndex(2)
					.forEachOrThrow(idxed -> pstmt.setObject(idxed.index(), idxed.value()));
			pstmt.setTimestamp(1, JdbcUtils.toTimestamp(Instant.now()));
			pstmt.execute();
			
			m_lastValues = values;
		});
	}
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Object toJdbcObject(ElementLocation elmLoc) {
		SubmodelElement element = m_faaast.getSubmodelElementOfLocation(elmLoc);
		if ( element instanceof Property prop ) {
			DataType type = DataTypes.fromAas4jDatatype(prop.getValueType());
			Object propValue = type.parseValueString(prop.getValue());
			return type.toJdbcObject(propValue);
		}
		else {
			return MDTModelSerDe.toJsonString(ElementValues.getValue(element));
		}
    }
	
	@Override
	public String toString() {
		return "Audit: " + m_config;
	}
}
