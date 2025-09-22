package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import utils.async.Guard;
import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;

import mdt.persistence.asset.AbstractAssetVariable;
import mdt.persistence.asset.AssetVariable;
import mdt.persistence.asset.AssetVariableException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractJdbcAssetVariable<T extends AbstractJdbcAssetVariableConfig>
																				extends AbstractAssetVariable<T>
																				implements AssetVariable {
	private final JdbcProcessor m_jdbc;
	private final Guard m_guard = Guard.create();

	abstract protected void loadOnBuffer(Connection conn) throws AssetVariableException;

	/**
	 * 갱신된 prototype을 DBMS에 저장한다.
	 * 
	 * @param conn	 DBMS와 연결된 Connection 객체
	 * @throws AssetVariableException	 JDBC를 통한 저장이 실패한 경우.
	 */
	abstract protected void saveBuffer(Connection conn) throws AssetVariableException;
	
	public AbstractJdbcAssetVariable(T config) {
		super(config);
		setLogger(LoggerFactory.getLogger(getClass()));

		JdbcConfiguration jdbcConf = config.getJdbcConfig();
		m_jdbc = JdbcProcessor.create(jdbcConf);
	}

	@Override
	public SubmodelElement read() {
		Preconditions.checkState(isReadable(), "This AssetVariable is not readable");
		assertJdbcProcessor();
		
		// 'm_prototype' 값을 공유하여 동시성 문제를 방지하기 위해 Guard를 사용한다.
		m_guard.lock();
		try {
			if ( isExpired(Instant.now()) ) {
				try ( Connection conn = m_jdbc.connect(); ) {
					loadOnBuffer(conn);
					
					// Database에서 새로 읽었기 때문에 access-time을 갱신한다.
					setLastAccessTime(Instant.now());
				}
				catch ( SQLException e ) {
					String msg = String.format("Failed to read %s", this);
					throw new AssetVariableException(msg, e);
				}
			}
			
			return m_prototype;
		}
		finally {
			m_guard.unlock();
		}
	}

	@Override
	public void update(SubmodelElement sme) {
		if ( !isUpdatable() ) {
			throw new AssetVariableException("This AssetVariable is not updateable");
		}
		
		assertJdbcProcessor();
		
		m_guard.lock();
		try ( Connection conn = m_jdbc.connect() ) {
			if ( sme != m_prototype ) {
				m_prototype = sme;
			}
			saveBuffer(conn);
			
			setLastAccessTime(Instant.now());
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to update %s", this);
			throw new AssetVariableException(msg, e);
		}
		finally {
			m_guard.unlock();
		}
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s, jdbc=%s]",
							getClass().getSimpleName(), getElementLocation(),
							getConfig().getJdbcConfig());
	}
	
	private void assertJdbcProcessor() {
		Preconditions.checkState(m_jdbc != null, "JdbcProcess has not been set");
	}
}
