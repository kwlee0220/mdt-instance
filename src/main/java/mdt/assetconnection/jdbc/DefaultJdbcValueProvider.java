package mdt.assetconnection.jdbc;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import utils.jdbc.JdbcProcessor;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetValueProvider;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ValueMappingException;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.DataElementValue;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.mapper.ElementValueMapper;
import de.fraunhofer.iosb.ilt.faaast.service.util.ReferenceHelper;
import mdt.FaaastRuntime;
import mdt.model.MDTModelSerDe;
import mdt.model.MDTSubstitutor;
import mdt.model.ReferenceUtils;
import mdt.model.ResourceNotFoundException;
import mdt.persistence.asset.jdbc.SimpleJdbcAssetVariable;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DefaultJdbcValueProvider implements AssetValueProvider {
	private final DefaultJdbcValueProviderConfig m_config;
	private final SimpleJdbcAssetVariable m_variable;
	private Instant m_lastAccessTime;

	public DefaultJdbcValueProvider(ServiceContext serviceContext, Reference reference,
									DefaultJdbcValueProviderConfig config, JdbcProcessor jdbc)
		throws ResourceNotFoundException, IOException {
		m_config = config;
		
		ReferenceUtils.assertSubmodelElementReference(reference);
		String submodelId = ReferenceHelper.getRoot(reference).getValue();
		String path = IdShortPath.fromReference(reference).toString();
		
		String jsonStr = MDTModelSerDe.toJsonString(config);
		String substituted = MDTSubstitutor.substibute(jsonStr);
		config = MDTModelSerDe.readValue(substituted, DefaultJdbcValueProviderConfig.class);

		FaaastRuntime faaast = new FaaastRuntime(serviceContext);
		Submodel submodel = faaast.getSubmodelById(submodelId);
		String smIdShort = submodel.getIdShort();
		m_variable = null;
//		m_variable = new JdbcAssetParameter(path, config.getReadQuery(), config.getUpdateQuery());
//		m_variable.initialize(submodel);
//		m_variable.setJdbcProcessor(jdbc);
		
		m_lastAccessTime = Instant.now().minus(Duration.ofDays(1));
	}

	@Override
	public DataElementValue getValue() throws AssetConnectionException {
		try {
//			SubmodelElement element = m_variable.getElementBuffer();
			SubmodelElement element = null;
			if ( m_config.getValidPeriod() != null ) {
				Instant now = Instant.now();
				if ( Duration.between(m_lastAccessTime, now).compareTo(m_config.getValidPeriod()) > 0 ) {
//					m_variable.load(null, null);
					m_lastAccessTime = now;
				}
			}
			
			return ElementValueMapper.toValue(element, DataElementValue.class);
		}
		catch ( ValueMappingException e ) {
			String msg = String.format("Failed to get %s, cause=%s", m_variable, e);
			throw new AssetConnectionException(msg, e);
		}
	}

	@Override
	public void setValue(DataElementValue value) throws AssetConnectionException {
		try {
			ElementValueMapper.setValue(null, value);
//			m_variable.save(null, null);
			
			m_lastAccessTime = Instant.now();
		}
		catch ( ValueMappingException e ) {
			String msg = String.format("Failed to store %s, cause=%s", m_variable, e);
			throw new AssetConnectionException(msg, e);
		}
	}
}
