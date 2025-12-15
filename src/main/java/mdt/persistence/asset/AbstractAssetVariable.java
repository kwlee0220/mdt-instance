package mdt.persistence.asset;

import java.time.Duration;
import java.time.Instant;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.LoggerSettable;
import utils.func.Optionals;
import utils.stream.FStream;

import mdt.ElementLocation;
import mdt.model.ResourceNotFoundException;
import mdt.model.sm.SubmodelUtils;
import mdt.persistence.MDTModelLookup;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractAssetVariable<T extends AssetVariableConfig> implements AssetVariable, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractAssetVariable.class);
	
	protected final T m_config;
	protected SubmodelElement m_prototype;
	private Instant m_lastAccessTime = Instant.EPOCH;
	private Logger m_logger = s_logger;
	
	protected AbstractAssetVariable(T config) {
		m_config = config;
	}

	@Override
	public ElementLocation getElementLocation() {
		return m_config.getElementLocation();
	}
	
	public T getConfig() {
		return m_config;
	}
	
	public Duration getValidPeriod() {
		return m_config.getValidPeriod();
	}
	
	public boolean isExpired(Instant ts) {
		Duration elapsed = Duration.between(getLastAccessTime(), ts);
		return elapsed.compareTo(getValidPeriod()) > 0;
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		ElementLocation loc = getElementLocation();
		loc.activate(lookup);

		// 검색된 Submodel 내에서 본 element에 해당하는 SubmodelElement를 찾는다.
		Submodel submodel
			= FStream.from(lookup.getSubmodelAll())
					.findFirst(sm -> sm.getIdShort().equals(loc.getSubmodelIdShort()))
					.getOrThrow(() -> new ResourceNotFoundException("Submodel", "idShort=" + loc.getSubmodelIdShort()));
		m_prototype = SubmodelUtils.traverse(submodel, getElementLocation().getElementPath());
	}
	
	protected Instant getLastAccessTime() {
        return m_lastAccessTime;
	}

	/**
	 * 마지막 접근 시간을 설정한다.
	 * 
	 * @param time 접근 시간
	 */
	protected void setLastAccessTime(Instant time) {
		m_lastAccessTime = time;
	}

	@Override
	public Logger getLogger() {
		return m_logger;
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = Optionals.getOrElse(logger, s_logger);
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), getElementLocation());
	}
}