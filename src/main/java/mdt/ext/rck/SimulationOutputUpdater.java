package mdt.ext.rck;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private static final Logger s_logger = LoggerFactory.getLogger(SimulationOutputUpdater.class);
	
	private MDTArgumentReference m_progressRef;
	private MDTArgumentReference m_productionRef;
	private MDTArgumentReference m_utilizationRef;
	
	public SimulationOutputUpdater(MDTInstanceManager manager, String instanceId) {
		m_progressRef = getArgumentReference(manager, instanceId, "Progress");
		m_productionRef = getArgumentReference(manager, instanceId, "Production");
		m_utilizationRef = getArgumentReference(manager, instanceId, "AverageUtilization");
	}
	
	public void update(RCKSimulationResult result) {
		try {
			m_progressRef.updatePropertyValue(PropertyValue.INTEGER(result.getProgress()));
			m_productionRef.updatePropertyValue(PropertyValue.INTEGER(result.getProduction()));
			
			Map<String, ElementValue> utilsMap = KeyValueFStream.from(result.getUtilizations())
																.mapValue(v -> (ElementValue)PropertyValue.FLOAT(v))
																.toMap();
			m_utilizationRef.updateValue(new ElementCollectionValue(utilsMap));
		}
		catch ( Exception e ) {
			s_logger.warn("failed to update simulation status", e);
		}
	}

	private MDTArgumentReference getArgumentReference(MDTInstanceManager manager, String instId,
														String argName) {
		String argExpr = String.format("oparg:%s:PressSimulation:out:%s", instId, argName);
		ElementReference ref = ElementReferences.parseExpr(argExpr);
		if ( ref instanceof MDTArgumentReference argRef ) {
			argRef.activate(manager);
			return argRef;
		}
		else {
			throw new IllegalArgumentException("Target element is not MDTElementReference: " + ref);
		}
	}
}
