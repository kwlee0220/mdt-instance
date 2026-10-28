package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import utils.Preconditions;

import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;


/**
 * 단일 SubmodelElement를 하나의 스칼라 SQL 조회/갱신 질의에 연결하는
 * {@link AbstractJdbcAssetVariable} 구현.
 * <p>
 * 설정({@link SimpleJdbcAssetVariableConfig})의 read query는 한 행·한 컬럼의 스칼라 값을 반환하는
 * {@code SELECT}여야 하며, 그 값이 prototype SubmodelElement에 반영된다. update query는 하나의
 * 파라미터(prototype의 값)를 받는 DML({@code INSERT}/{@code UPDATE}) 질의로, prototype의 값을 datasource에
 * 기록한다. 질의가 한 행도 바꾸지 않으면(예: {@code WHERE}에 맞는 행이 없음) 갱신은 실패한다.
 * 조회 결과에 행이 없을 때의 동작은 설정의 {@code onNoRecord}를 따른다.
 * SubmodelElement와 JDBC 값 사이의 변환은 {@link JdbcColumnBinding}이 담당한다.
 * <p>
 * update query가 설정되지 않은 경우 이 변수는 읽기 전용이다({@link #isUpdatable()}이 {@code false}).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SimpleJdbcAssetVariable extends AbstractJdbcAssetVariable<SimpleJdbcAssetVariableConfig> {
	private JdbcColumnBinding m_binding;

	/**
	 * 주어진 설정으로 {@code SimpleJdbcAssetVariable}을 생성한다.
	 *
	 * @param config	read/update query를 포함하는 설정 객체.
	 */
	public SimpleJdbcAssetVariable(SimpleJdbcAssetVariableConfig config) {
		super(config);
	}

	/**
	 * AAS SubmodelElement에 바인딩한 뒤, 바인딩된 prototype을 기반으로
	 * SubmodelElement와 JDBC 값 사이의 변환을 담당하는 {@link JdbcColumnBinding}을 생성한다.
	 *
	 * @param lookup	연결할 SubmodelElement 접속에 사용할 lookup 객체.
	 */
	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);

		m_binding = JdbcColumnBinding.of(m_prototype, "");
	}

	/**
	 * 이 변수가 읽기 가능한지 여부를 반환한다.
	 *
	 * @return read query가 설정되어 있으면 {@code true}.
	 */
	@Override
	public boolean isReadable() {
		return getConfig().getReadQuery() != null;
	}

	/**
	 * 이 변수가 갱신 가능한지 여부를 반환한다.
	 *
	 * @return update query가 설정되어 있으면 {@code true}.
	 */
	@Override
	public boolean isUpdatable() {
        return getConfig().getUpdateQuery() != null;
    }

	/**
	 * read query를 실행하여 첫 행의 첫 컬럼 값을 버퍼에 반영한다.
	 * <p>
	 * 조회 결과가 없으면 설정의 {@code onNoRecord}({@link NoRecordPolicy})를 따른다.
	 *
	 * @param conn		DBMS와 연결된 Connection 객체.
	 * @param buffer	값을 채울 SubmodelElement.
	 * @throws AssetVariableException	조회가 실패했거나, 행이 없고 {@code onNoRecord}가 {@code FAIL}인 경우.
	 */
	@Override
	protected void loadOnBuffer(Connection conn, SubmodelElement buffer) throws AssetVariableException {
		Preconditions.checkNotNullArgument(conn, "Connection is null");

		try ( Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(getConfig().getReadQuery())) {
			if ( rs.next() ) {
				onRecordFound();
				m_binding.read(rs, 1, buffer);
			}
			else {
				handleNoRecord(getConfig().getNoRecordPolicy(), getConfig().getReadQuery(),
								() -> m_binding.clear(buffer));
			}
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read %s", this);
			throw new AssetVariableException(msg, e);
		}
	}
	
	/**
	 * 주어진 SubmodelElement의 값을 update query의 단일 파라미터로 바인딩하여 DBMS에 기록한다.
	 *
	 * @param conn	DBMS와 연결된 Connection 객체.
	 * @param value	저장할 SubmodelElement.
	 * @throws AssetVariableException	저장이 실패했거나 질의가 한 행도 바꾸지 않은 경우.
	 */
	@Override
	protected void saveBuffer(Connection conn, SubmodelElement value) throws AssetVariableException {
		int count;
		try ( PreparedStatement pstmt = conn.prepareStatement(getConfig().getUpdateQuery()) ) {
			m_binding.bind(pstmt, 1, value);
			count = pstmt.executeUpdate();
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to write %s with query: %s", this, getConfig().getUpdateQuery());
			throw new AssetVariableException(msg, e);
		}
		if ( count == 0 ) {
			String msg = String.format("No row was updated for %s with query: %s", this, getConfig().getUpdateQuery());
			throw new AssetVariableException(msg);
		}
	}
}
