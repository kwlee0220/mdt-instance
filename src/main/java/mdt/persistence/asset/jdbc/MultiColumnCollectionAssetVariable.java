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
import mdt.persistence.asset.jdbc.MultiColumnCollectionAssetVariableConfig.UpdateMode;


/**
 * 테이블 한 행의 여러 컬럼을 SubmodelElementCollection의 하위 element들에 연결하는
 * {@link AbstractJdbcAssetVariable} 구현.
 * <p>
 * 조회 결과의 첫 행을 읽어, 각 컬럼 값을 매핑된 하위 element에 반영한다. 컬럼은 이름이 아니라 조회 질의의
 * {@code SELECT} 목록 위치(매핑 순서)로 읽으므로 함수식이나 별칭을 쓴 컬럼 표현식도 읽을 수 있다.
 * 조회 결과에 행이 없을 때의 동작은 설정의 {@code onNoRecord}를 따른다.
 * 쓰기는 설정의 {@code updateMode}에 따라 새 행을 추가하거나 조건에 맞는 행을 갱신하며,
 * 한 행도 바뀌지 않거나 두 행 이상이 바뀌면 실패로 처리하고 트랜잭션을 되돌린다.
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
		checkNoRecordPolicy(getConfig().getNoRecordPolicy(), m_bindings);
	}

	/**
	 * 읽기 전용 매핑이 있으면 저장 후 캐시를 만료시킨다.
	 * <p>
	 * 읽기 전용 매핑의 값은 쓰기 SQL에서 빠지므로 저장 요청의 값이 DB에 기록되지 않으며, 계산 컬럼이면
	 * 다른 컬럼의 변경에 따라 DB의 값이 바뀐다. 따라서 저장 요청의 값을 캐시로 쓰지 않고 다시 읽는다.
	 */
	@Override
	protected boolean isReloadNeededAfterSave() {
		return FStream.from(getConfig().getColumnToSubPathMappings()).exists(mapping -> !mapping.isWritable());
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
		try ( Statement stmt = createStatement(conn);
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
		// 갱신 질의의 파라미터는 쓰기에 포함되는 매핑의 순서대로 구성된다(readOnly 매핑 제외).
		executeUpdate(conn, getConfig().getUpdateQuery(), getConfig().getWritableMappingIndices(), value);
	}
	
	/**
	 * {@link UpdateMode#UPDATE} 모드에서는 바뀐 하위 요소에 매핑된 컬럼만 갱신한다.
	 * <p>
	 * 다른 컬럼까지 함께 저장하면, 그 사이 다른 프로그램이 바꾼 컬럼 값을 덮어쓰게 된다.
	 * {@link UpdateMode#APPEND} 모드는 새 행에 모든 컬럼이 필요하므로 전체를 저장한다.
	 * 바뀐 하위 요소가 어떤 컬럼에도 매핑되어 있지 않으면 DB에 쓰지 않는다.
	 */
	@Override
	protected void savePart(Connection conn, SubmodelElement whole, String relPath) throws AssetVariableException {
		// 읽기 전용 매핑만 바꾸는 요청은 DB에 기록할 수 없으므로 성공으로 처리하지 않고 거부한다.
		List<String> readOnlyTargets = FStream.range(0, m_bindings.size())
											.filter(i -> overlaps(m_bindings.get(i).getSubPath(), relPath))
											.filter(i -> !getConfig().getColumnToSubPathMappings().get(i).isWritable())
											.map(i -> m_bindings.get(i).getSubPath())
											.toList();
		boolean writableTarget = FStream.from(getConfig().getWritableMappingIndices())
										.exists(i -> overlaps(m_bindings.get(i).getSubPath(), relPath));
		if ( !readOnlyTargets.isEmpty() && !writableTarget ) {
			throw new AssetVariableException(String.format("Cannot update read-only columns of %s: subPaths=%s",
															this, readOnlyTargets));
		}
		
		if ( getConfig().getUpdateMode() != UpdateMode.UPDATE ) {
			super.savePart(conn, whole, relPath);
			return;
		}
		
		List<Integer> affected = FStream.from(getConfig().getWritableMappingIndices())
										.filter(i -> overlaps(m_bindings.get(i).getSubPath(), relPath))
										.toList();
		if ( !affected.isEmpty() ) {
			executeUpdate(conn, getConfig().getUpdateQuery(affected), affected, whole);
		}
	}
	
	private void executeUpdate(Connection conn, String query, List<Integer> mappingIndices, SubmodelElement value)
		throws AssetVariableException {
		int count;
		try ( PreparedStatement pstmt = prepareStatement(conn, query) ) {
			for ( int i = 0; i < mappingIndices.size(); ++i ) {
				m_bindings.get(mappingIndices.get(i)).bind(pstmt, i + 1, value);
			}
			count = pstmt.executeUpdate();
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to write %s with query: %s", this, query);
			throw new AssetVariableException(msg, e);
		}
		if ( count == 0 ) {
			// UPDATE 모드에서 whereClause에 맞는 행이 없는 경우.
			String msg = String.format("No row was updated for %s with query: %s", this, query);
			throw new AssetVariableException(msg);
		}
		if ( count > 1 ) {
			// 이 변수는 SubmodelElementCollection 하나를 한 행에 연결하므로, 여러 행이 바뀌었다면 조건이 잘못된 것이다.
			// 예외를 던지면 호출자가 트랜잭션을 되돌리므로 실제로는 어떤 행도 바뀌지 않는다.
			String msg = String.format("%d rows were updated for %s (expected 1), rolled back: query=%s",
										count, this, query);
			throw new AssetVariableException(msg);
		}
	}
}
