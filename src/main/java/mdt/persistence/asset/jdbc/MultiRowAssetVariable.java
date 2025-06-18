package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import utils.KeyValue;
import utils.stream.KeyValueFStream;

import mdt.model.ResourceNotFoundException;
import mdt.model.sm.SubmodelUtils;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiRowAssetVariable extends AbstractJdbcAssetVariable<MultiRowAssetVariableConfig> {
	private Map<String,SubmodelElementHandler> m_memberHandlers;
	
	public MultiRowAssetVariable(MultiRowAssetVariableConfig config) {
		super(config);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);

		m_memberHandlers = KeyValueFStream.from(getConfig().getKeyToSubpathMapping())
											.mapKeyValue((key, subpath) -> {
												SubmodelElement member = SubmodelUtils.traverse(m_prototype, subpath);
												SubmodelElementHandler handler = new SubmodelElementHandler(member);
												return KeyValue.of(key, handler);
											})
											.toMap();
	}

	@Override
	public boolean isUpdateable() {
        return getConfig().getUpdateQuery() != null;
    }

	@Override
	protected void loadOnBuffer(Connection conn) throws AssetVariableException, ResourceNotFoundException {
		Map<String,String> keyToSubpath = getConfig().getKeyToSubpathMapping();
		try ( Statement stmt = conn.createStatement(); ) {
			ResultSet rs = stmt.executeQuery(getConfig().getReadQuery());
			while ( rs.next() ) {
				String key = rs.getString(1);
				Object cvalue = rs.getObject(2);
				
				SubmodelElementHandler handler = m_memberHandlers.get(key);
				String subPath = keyToSubpath.get(key);
				if ( subPath != null ) {
					SubmodelElement part = SubmodelUtils.traverse(m_prototype, subPath);
					handler.update(part, cvalue);
				}
				else {
					if ( getLogger().isDebugEnabled() ) {
						getLogger().debug("Skip Row: key={}", key);
					}
				}
			}
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read %s", this);
			throw new AssetVariableException(msg, e);
		}
	}

	@Override
	protected void saveBuffer(Connection conn) throws AssetVariableException {
		try ( PreparedStatement pstmt = conn.prepareStatement(getConfig().getUpdateQuery()); ) {
			for ( Map.Entry<String,String> ent: getConfig().getKeyToSubpathMapping().entrySet() ) {
				SubmodelElement part = SubmodelUtils.traverse(m_prototype, ent.getValue());
				SubmodelElementHandler handler = m_memberHandlers.get(ent.getKey());
				pstmt.setObject(1, handler.toJdbcObject(part));
				pstmt.setString(2, ent.getKey());
				pstmt.executeUpdate();
			}
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to save %s: path=%s", this, getElementLocation().getElementPath());
			throw new AssetVariableException(msg, e);
		}
	}
}
