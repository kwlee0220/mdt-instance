package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;


/**
 * key-value 형태의 여러 행을 SubmodelElementCollection의 하위 element들에 연결하는
 * {@link AbstractJdbcAssetVariable} 구현.
 * <p>
 * 조회 질의는 행마다 첫 컬럼에 key, 둘째 컬럼에 값을 돌려줘야 하며, 설정의 {@code rows}에 매핑된 key의 값이
 * 해당 하위 element에 반영된다. 매핑에 없는 key의 행은 건너뛴다. 갱신 질의는 첫 파라미터에 값, 둘째 파라미터에
 * key를 받는 {@code UPDATE}여야 하며, 매핑된 모든 key를 하나의 트랜잭션으로 갱신한다. 어느 key의 행이라도
 * 없으면 갱신 전체가 실패하고 되돌려진다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiRowAssetVariable extends AbstractJdbcAssetVariable<MultiRowAssetVariableConfig> {
	private Map<String,JdbcColumnBinding> m_bindings;	// key → 값 컬럼 바인딩
	
	public MultiRowAssetVariable(MultiRowAssetVariableConfig config) {
		super(config);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);

		// 갱신 순서가 설정의 순서를 따르도록 LinkedHashMap을 쓴다.
		m_bindings = new LinkedHashMap<>();
		getConfig().getKeyToSubpathMapping()
					.forEach((key, subPath) -> m_bindings.put(key, JdbcColumnBinding.of(m_prototype, subPath)));
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
	protected void loadOnBuffer(Connection conn, SubmodelElement buffer) throws AssetVariableException {
		try ( Statement stmt = conn.createStatement(); ) {
			ResultSet rs = stmt.executeQuery(getConfig().getReadQuery());
			while ( rs.next() ) {
				String key = rs.getString(1);
				JdbcColumnBinding binding = m_bindings.get(key);
				if ( binding != null ) {
					binding.read(rs, 2, buffer);
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
	protected void saveBuffer(Connection conn, SubmodelElement value) throws AssetVariableException {
		try ( PreparedStatement pstmt = conn.prepareStatement(getConfig().getUpdateQuery()); ) {
			for ( Map.Entry<String,JdbcColumnBinding> ent: m_bindings.entrySet() ) {
				ent.getValue().bind(pstmt, 1, value);
				pstmt.setString(2, ent.getKey());
				if ( pstmt.executeUpdate() == 0 ) {
					// 해당 key의 행이 없다. 예외를 던지면 호출자가 앞서 갱신한 행까지 되돌린다.
					String msg = String.format("No row was updated for %s: key=%s, query=%s",
												this, ent.getKey(), getConfig().getUpdateQuery());
					throw new AssetVariableException(msg);
				}
			}
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to save %s: path=%s", this, getElementLocation().getElementPath());
			throw new AssetVariableException(msg, e);
		}
	}
}
