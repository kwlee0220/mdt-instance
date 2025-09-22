package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.google.common.base.Preconditions;

import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SimpleJdbcAssetVariable extends AbstractJdbcAssetVariable<SimpleJdbcAssetVariableConfig> {
	private SubmodelElementHandler m_handler;
	
	public SimpleJdbcAssetVariable(SimpleJdbcAssetVariableConfig config) {
		super(config);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);
		
		m_handler = new SubmodelElementHandler(m_prototype);
	}

	@Override
	public boolean isReadable() {
		return getConfig().getReadQuery() != null;
	}

	@Override
	public boolean isUpdatable() {
        return getConfig().getUpdateQuery() != null;
    }

	@Override
	public void loadOnBuffer(Connection conn) throws AssetVariableException {
		Preconditions.checkArgument(conn != null, "Connection is null");

		try ( Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(getConfig().getReadQuery())) {
			if ( rs.next() ) {
				Object value = rs.getObject(1);
				m_handler.update(m_prototype, value);
			}
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read %s", this);
			throw new AssetVariableException(msg, e);
		}
	}
	
	public void saveBuffer(Connection conn) throws AssetVariableException {
		try ( PreparedStatement pstmt = conn.prepareStatement(getConfig().getUpdateQuery()) ) {
			Object jdbcValue = m_handler.toJdbcObject(m_prototype); 
			
			pstmt.setObject(1, jdbcValue);
			pstmt.execute();
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to write %s with query: %s", this, getConfig().getUpdateQuery());
			throw new AssetVariableException(msg, e);
		}
	}
}
