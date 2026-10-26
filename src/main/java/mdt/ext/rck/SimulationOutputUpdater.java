package mdt.ext.rck;

import java.io.IOException;
import java.util.LinkedHashMap;

import utils.stream.KeyValueFStream;

import mdt.model.instance.MDTInstanceManager;
import mdt.model.sm.ref.ElementReference;
import mdt.model.sm.ref.ElementReferences;
import mdt.model.sm.ref.MDTArgumentReference;
import mdt.model.sm.value.ElementCollectionValue;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.PropertyValue;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SimulationOutputUpdater {
	/** 시뮬레이션 결과가 기록되는 MDT 오퍼레이션의 이름. */
	static final String OPERATION_NAME = "PressSimulation";
	private static final String ARGUMENT_EXPR_FORMAT = "oparg:%s:" + OPERATION_NAME + ":out:%s";

	private final MDTArgumentReference m_progressRef;
	private final MDTArgumentReference m_productionRef;
	private final MDTArgumentReference m_utilizationRef;

	public SimulationOutputUpdater(MDTInstanceManager manager, String instanceId) {
		m_progressRef = getArgumentReference(manager, instanceId, "Progress");
		m_productionRef = getArgumentReference(manager, instanceId, "Production");
		m_utilizationRef = getArgumentReference(manager, instanceId, "AverageUtilization");
	}

	/**
	 * 시뮬레이션 결과를 MDT 인스턴스의 출력 인자에 반영한다.
	 * <p>
	 * 반영에 실패한 경우 예외를 그대로 전파한다. 갱신 실패를 무시할지 여부는 호출자가 결정한다.
	 *
	 * @param result	반영할 시뮬레이션 결과.
	 * @throws IOException	출력 인자 갱신에 실패한 경우.
	 */
	public void update(RCKSimulationResult result) throws IOException {
		m_progressRef.updatePropertyValue(PropertyValue.INTEGER(result.getProgress()));
		m_productionRef.updatePropertyValue(PropertyValue.INTEGER(result.getProduction()));

		var utilsMap = KeyValueFStream.from(result.getUtilizations())
													.mapValue(v -> (ElementValue)PropertyValue.FLOAT(v))
													.toMap(new LinkedHashMap<>());
		m_utilizationRef.updateValue(new ElementCollectionValue(utilsMap));
	}

	/**
	 * MDT 인스턴스에 등록된 시뮬레이션 오퍼레이션의 출력 인자 reference를 생성·활성화한다.
	 *
	 * @param manager	MDT 인스턴스 매니저.
	 * @param instId	대상 인스턴스 식별자.
	 * @param argName	출력 인자 이름. (예: {@code "State"}, {@code "SimulationVideo"})
	 * @return	활성화된 인자 reference.
	 * @throws IllegalArgumentException	해당 표현식이 {@link MDTArgumentReference}로 해석되지 않는 경우.
	 */
	static MDTArgumentReference getArgumentReference(MDTInstanceManager manager, String instId,
													String argName) {
		String argExpr = String.format(ARGUMENT_EXPR_FORMAT, instId, argName);
		ElementReference ref = ElementReferences.parseExpr(argExpr);
		if ( ref instanceof MDTArgumentReference argRef ) {
			argRef.activate(manager);
			return argRef;
		}
		else {
			throw new IllegalArgumentException("Target element is not MDTArgumentReference: " + ref);
		}
	}
}
