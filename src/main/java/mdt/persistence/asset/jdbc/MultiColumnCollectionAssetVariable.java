package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import utils.stream.FStream;

import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;


/**
 * 테이블 한 행의 여러 컬럼을 SubmodelElementCollection의 하위 element들에 연결하는
 * {@link AbstractJdbcAssetVariable} 구현.
 * <p>
 * 조회 결과의 첫 행을 읽어, 각 컬럼 값을 매핑된 하위 element에 반영한다. 컬럼은 이름이 아니라 조회 질의의
 * {@code SELECT} 목록 위치(매핑 순서)로 읽으므로 함수식이나 별칭을 쓴 컬럼 표현식도 읽을 수 있다.
 * 조회 결과에 행이 없을 때의 동작은 설정의 {@code onNoRecord}를 따른다.
 * 쓰기는 설정의 {@code updateMode}에 따라 새 행을 추가하거나 조건에 맞는 행을 갱신하며,
 * 한 행도 바뀌지 않으면 실패로 처리한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiColumnCollectionAssetVariable
								extends AbstractJdbcAssetVariable<MultiColumnCollectionAssetVariableConfig> {
	private List<JdbcColumnBinding> m_bindings;	// 매핑 순서 (= SELECT 목록 순서)

	public MultiColumnCollectionAssetVariable(MultiColumnCollectionAssetVariableConfig config) {
		super(config);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);

		m_bindings = FStream.from(getConfig().getColumnToSubPathMappings())
							.map(mapping -> JdbcColumnBinding.of(m_prototype, mapping.getSubPath()))
							.toList();
	}

	@Override
	public boolean isReadable() {
		return getConfig().isReadable();
	}

	@Override
	public boolean isUpdatable() {
		return getConfig().isUpdatable();
	}

	@Override
	protected void loadOnBuffer(Connection conn, SubmodelElement buffer) throws AssetVariableException {
		try ( Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(getConfig().getReadQuery()) ) {
			if ( rs.next() ) {
				onRecordFound();
				for ( int i = 0; i < m_bindings.size(); ++i ) {
					// 조회 질의의 SELECT 목록은 매핑 순서대로 구성된다.
					m_bindings.get(i).read(rs, i + 1, buffer);
				}
			}
			else {
				handleNoRecord(getConfig().getNoRecordPolicy(), getConfig().getReadQuery(),
								() -> m_bindings.forEach(binding -> binding.clear(buffer)));
			}
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read %s", this);
			throw new AssetVariableException(msg, e);
		}
	}

	@Override
	protected void saveBuffer(Connection conn, SubmodelElement value) throws AssetVariableException {
		int count;
		try ( PreparedStatement pstmt = conn.prepareStatement(getConfig().getUpdateQuery()) ) {
			for ( int i = 0; i < m_bindings.size(); ++i ) {
				// 갱신 질의의 파라미터는 매핑 순서대로 구성된다.
				m_bindings.get(i).bind(pstmt, i + 1, value);
			}
			count = pstmt.executeUpdate();
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to write %s with query: %s", this, getConfig().getUpdateQuery());
			throw new AssetVariableException(msg, e);
		}
		if ( count == 0 ) {
			// UPDATE 모드에서 whereClause에 맞는 행이 없는 경우.
			String msg = String.format("No row was updated for %s with query: %s", this, getConfig().getUpdateQuery());
			throw new AssetVariableException(msg);
		}
	}
}
