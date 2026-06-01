package mdt.persistence.asset;

import java.time.Duration;
import java.time.Instant;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.LoggerSettable;
import utils.Preconditions;
import utils.func.FOption;

import mdt.ElementLocation;
import mdt.model.sm.SubmodelUtils;
import mdt.persistence.MDTModelLookup;


/**
 * {@link AssetVariable} 구현을 위한 추상 기반 클래스.
 * <p>
 * 외부 설비(asset)와 연계되는 단일 SubmodelElement를 다루는 데 공통적으로 필요한 기능을 제공한다.
 * <ul>
 *   <li>설정 객체({@code T}) 보관 및 이를 통한 {@link ElementLocation}, 유효 기간 등에 대한 접근.</li>
 *   <li>{@link #initialize(MDTModelLookup)}를 통한 AAS SubmodelElement 바인딩 및 prototype 초기화.</li>
 *   <li>마지막 갱신 시각과 유효 기간을 이용한 캐시 만료 판정({@link #isExpired(Instant)}).</li>
 *   <li>{@link LoggerSettable} 지원.</li>
 * </ul>
 * 외부 datasource로부터의 실제 읽기/쓰기({@link #read()}/{@link #update(SubmodelElement)})와
 * 읽기/쓰기 가능 여부({@link #isReadable()}/{@link #isUpdatable()})는 하위 클래스에서 구현한다.
 * <p>
 * {@code m_prototype}은 바인딩된 SubmodelElement의 캐시본으로, 하위 클래스가 datasource에서 읽은
 * 최신 값으로 갱신한다.
 *
 * @param <T>	이 {@code AssetVariable}의 설정 타입.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractAssetVariable<T extends AssetVariableConfig>
																implements AssetVariable, LoggerSettable {
	private static final Logger s_logger = LoggerFactory.getLogger(AbstractAssetVariable.class);
	
	protected final T m_config;
	protected SubmodelElement m_prototype;
	private Instant m_lastRefreshTime = Instant.EPOCH;
	private Logger m_logger = s_logger;

	protected AbstractAssetVariable(T config) {
		Preconditions.checkNotNullArgument(config, "config is null");
		m_config = config;
	}

	@Override
	public ElementLocation getElementLocation() {
		return m_config.getElementLocation();
	}
	
	/**
	 * 이 {@code AssetVariable}의 설정 객체를 반환한다.
	 *
	 * @return 설정 객체.
	 */
	public T getConfig() {
		return m_config;
	}

	/**
	 * 캐시된 prototype 값이 유효한 것으로 간주되는 기간을 반환한다.
	 * <p>
	 * 마지막 갱신 이후 이 기간이 경과하면 캐시된 값은 만료된 것으로 판정된다.
	 *
	 * @return 유효 기간.
	 */
	public Duration getValidPeriod() {
		return m_config.getValidPeriod();
	}

	/**
	 * 주어진 시각을 기준으로 캐시된 prototype 값이 만료되었는지 여부를 반환한다.
	 * <p>
	 * 마지막 갱신 시각으로부터 {@link #getValidPeriod()}보다 더 많은 시간이 경과한 경우 만료된 것으로 본다.
	 *
	 * @param ts 만료 여부를 판정할 기준 시각.
	 * @return 만료된 경우 {@code true}.
	 */
	public boolean isExpired(Instant ts) {
		Duration elapsed = Duration.between(getLastRefreshTime(), ts);
		return elapsed.compareTo(getValidPeriod()) > 0;
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		ElementLocation loc = getElementLocation();
		loc.activate(lookup);

		// 검색된 Submodel 내에서 본 element에 해당하는 SubmodelElement를 찾는다.
		Submodel submodel = lookup.getSubmodelByIdShort(loc.getSubmodelIdShort());
		m_prototype = SubmodelUtils.traverse(submodel, loc.getElementPath());
	}
	
	protected Instant getLastRefreshTime() {
        return m_lastRefreshTime;
	}

	/**
	 * 마지막 갱신 시각을 설정한다.
	 * <p>
	 * datasource로부터 prototype을 새로 읽거나 갱신한 시점에 호출되며, 캐시 만료 판정의 기준이 된다.
	 *
	 * @param time 갱신 시각.
	 */
	protected void setLastRefreshTime(Instant time) {
		m_lastRefreshTime = time;
	}

	@Override
	public Logger getLogger() {
		return m_logger;
	}

	@Override
	public void setLogger(Logger logger) {
		m_logger = FOption.getOrElse(logger, s_logger);
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s]", getClass().getSimpleName(), getElementLocation());
	}
}