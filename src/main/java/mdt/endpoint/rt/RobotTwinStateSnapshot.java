package mdt.endpoint.rt;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import utils.stream.KeyValueFStream;


/**
 * 특정 시점에 조회한 로봇 트윈의 전체 상태.
 * <p>
 * 각 변수의 최신값을 담고 있지만, 값들이 <b>동일 시각에 생성되었음을 의미하지는 않는다.</b>
 * 변수마다 발행 주기가 다르기 때문에 값들 사이의 정합이 필요한 경우에는
 * 각 변수의 {@link RobotTwinVariable#getStamp() stamp}를 직접 비교해야 한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RobotTwinStateSnapshot {
	private final Instant m_polledAt;
	private final Map<String,RobotTwinVariable> m_variables;

	/**
	 * 조회된 변수들로 상태 스냅샷을 생성한다.
	 *
	 * @param polledAt  조회 시각.
	 * @param variables 변수 이름을 키로 하는 변수 값들.
	 */
	public RobotTwinStateSnapshot(Instant polledAt, Map<String,RobotTwinVariable> variables) {
		m_polledAt = polledAt;
		m_variables = Collections.unmodifiableMap(variables);
	}

	/**
	 * 상태 조회가 수행된 시각을 반환한다.
	 *
	 * @return 조회 시각.
	 */
	public Instant getPolledAt() {
		return m_polledAt;
	}

	/**
	 * 조회된 전체 변수를 반환한다.
	 *
	 * @return 변수 이름을 키로 하는 수정 불가능한 map.
	 */
	public Map<String,RobotTwinVariable> getVariables() {
		return m_variables;
	}

	/**
	 * 조회된 변수들의 이름을 반환한다.
	 *
	 * @return 변수 이름 집합.
	 */
	public Set<String> getVariableNames() {
		return m_variables.keySet();
	}

	/**
	 * 주어진 이름의 변수를 반환한다.
	 *
	 * @param name 변수 이름.
	 * @return 해당 변수. 조회되지 않은 변수인 경우는 {@code null}.
	 */
	public @Nullable RobotTwinVariable getVariable(String name) {
		return m_variables.get(name);
	}

	@Override
	public String toString() {
		String varsStr = KeyValueFStream.from(m_variables)
										.map((name, var) -> var.toString())
										.join(", ");
		return String.format("RobotTwinState[polledAt=%s, %s]", m_polledAt, varsStr);
	}
}
