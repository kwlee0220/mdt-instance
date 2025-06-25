package mdt.persistence.opcua;

import java.io.IOException;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.stream.FStream;

import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.asset.AssetVariableException;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OpcUaListAssetVariable extends AbstractOpcUaAssetVariable<OpcUaListAssetVariableConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(OpcUaListAssetVariable.class);
	
	public OpcUaListAssetVariable(OpcUaListAssetVariableConfig config) throws ConfigurationInitializationException {
		super(config);
		
		setLogger(s_logger);
	}

	@Override
	public SubmodelElement read() throws AssetVariableException {
		SubmodelElementList sml = (SubmodelElementList)m_prototype;
		FStream.from(m_config.getIdentifierAll())
				.zipWith(FStream.from(sml.getValue()))
				.forEachOrThrow(pair -> {
					int opcuaId = pair._1;
					SubmodelElement buffer = pair._2;

					Double newValue = readNode(opcuaId);
					try {
						ElementValues.updateWithRawValueString(buffer, "" +newValue);
					}
					catch ( IOException e ) {
						String msg = String.format("Unexpected OPC-UA value: identifier=%d, value=%s, cause=%s",
													opcuaId, newValue, e.getMessage());
						throw new AssetVariableException(msg, e);
					}
				});
		
		return SubmodelUtils.duplicate(sml);
	}
}
