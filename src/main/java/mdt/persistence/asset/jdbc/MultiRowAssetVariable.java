package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import utils.stream.FStream;

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
	// 직전 조회에서 행이 없었던 key들. 같은 key에 대해 경고를 반복하지 않기 위해 쓴다.
	private Set<String> m_reportedMissingKeys = Set.of();
	
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

	/**
	 * 조회 결과에 행이 없는 key를 알린다. 해당 하위 요소는 이전 값(처음이면 모델의 초기값)을 유지한다.
	 * <p>
	 * 행이 없어진 key는 처음 한 번만 경고하고, 그 key의 행이 다시 나타난 뒤 또 없어지면 다시 경고한다.
	 * 읽기는 캐시 잠금 안에서 수행되므로 따로 동기화하지 않는다.
	 */
	private void reportMissingKeys(Set<String> missing) {
		Set<String> newlyMissing = new LinkedHashSet<>(missing);
		newlyMissing.removeAll(m_reportedMissingKeys);
		if ( !newlyMissing.isEmpty() ) {
			getLogger().warn("No row for keys {} of {}, keeping their previous values: query={}",
							newlyMissing, this, getConfig().getReadQuery());
		}
		m_reportedMissingKeys = missing;
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
		try ( Statement stmt = createStatement(conn); ) {
			ResultSet rs = stmt.executeQuery(getConfig().getReadQuery());
			Set<String> missing = new LinkedHashSet<>(m_bindings.keySet());
			while ( rs.next() ) {
				String key = rs.getString(1);
				JdbcColumnBinding binding = m_bindings.get(key);
				if ( binding != null ) {
					binding.read(rs, 2, buffer);
					missing.remove(key);
				}
				else {
					if ( getLogger().isDebugEnabled() ) {
						getLogger().debug("Skip Row: key={}", key);
					}
				}
			}
			reportMissingKeys(missing);
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read %s", this);
			throw new AssetVariableException(msg, e);
		}
	}

	@Override
	protected void saveBuffer(Connection conn, SubmodelElement value) throws AssetVariableException {
		saveRows(conn, value, m_bindings.keySet());
	}
	
	/**
	 * 바뀐 하위 요소에 매핑된 key의 행만 갱신한다.
	 * <p>
	 * 다른 key의 행까지 함께 저장하면, 그 사이 다른 프로그램이 바꾼 행의 값을 덮어쓰게 된다.
	 * 바뀐 하위 요소가 어떤 key에도 매핑되어 있지 않으면 DB에 쓰지 않는다.
	 */
	@Override
	protected void savePart(Connection conn, SubmodelElement whole, String relPath) throws AssetVariableException {
		List<String> keys = FStream.from(m_bindings.entrySet())
									.filter(ent -> overlaps(ent.getValue().getSubPath(), relPath))
									.map(Map.Entry::getKey)
									.toList();
		saveRows(conn, whole, keys);
	}
	
	private void saveRows(Connection conn, SubmodelElement value, Collection<String> keys)
		throws AssetVariableException {
		if ( keys.isEmpty() ) {
			return;
		}
		try ( PreparedStatement pstmt = prepareStatement(conn, getConfig().getUpdateQuery()); ) {
			for ( String key: keys ) {
				m_bindings.get(key).bind(pstmt, 1, value);
				pstmt.setString(2, key);
				if ( pstmt.executeUpdate() == 0 ) {
					// 해당 key의 행이 없다. 예외를 던지면 호출자가 앞서 갱신한 행까지 되돌린다.
					String msg = String.format("No row was updated for %s: key=%s, query=%s",
												this, key, getConfig().getUpdateQuery());
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
