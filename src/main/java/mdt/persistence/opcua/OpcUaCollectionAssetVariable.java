package mdt.persistence.opcua;

import java.io.IOException;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
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
public class OpcUaCollectionAssetVariable extends AbstractOpcUaAssetVariable<OpcUaCollectionAssetVariableConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(OpcUaCollectionAssetVariable.class);
	
	public OpcUaCollectionAssetVariable(OpcUaCollectionAssetVariableConfig config)
		throws ConfigurationInitializationException {
		super(config);
		
		setLogger(s_logger);
	}

	@Override
	public SubmodelElement read() throws AssetVariableException {
		// subpath -> opcua identifier mapping
		SubmodelElementCollection smc = (SubmodelElementCollection)m_prototype;
		FStream.from(m_config.getFieldMappings())
				.forEach(mapping -> {
					SubmodelElement part = SubmodelUtils.traverse(smc, mapping.subPath());
					Double newValue = readNode(mapping.opcuaId());
					try {
						ElementValues.updateWithRawValueString(part, "" +newValue);
					}
					catch ( IOException e ) {
						String msg = String.format("Unexpected OPC-UA value: path=%s, identifier=%d, cause=%s",
													mapping.opcuaId(), newValue, e.getMessage());
						throw new AssetVariableException(msg, e);
					}
				});
		
		return SubmodelUtils.duplicate(smc);
	}
}
