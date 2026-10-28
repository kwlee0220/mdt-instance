package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.LoggerFactory;

import utils.Preconditions;
import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;
import utils.thread.Guard;

import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.asset.AbstractAssetVariable;
import mdt.persistence.asset.AssetVariableException;


/**
 * 관계형 DBMS를 datasource로 사용하는 {@code AssetVariable} 구현을 위한 추상 기반 클래스.
 * <p>
 * {@link JdbcProcessor}를 통해 DB에 접속하여 prototype SubmodelElement를 읽고 쓰며,
 * {@link AbstractAssetVariable}이 제공하는 유효 기간 기반 캐시를 사용한다. 즉 {@link #read()} 호출 시
 * 캐시가 만료된 경우에만 DB에서 다시 읽어온다. 공유되는 prototype에 대한 동시 접근 문제를 막기 위해
 * 모든 읽기/쓰기는 {@link Guard}로 직렬화된다.
 * <p>
 * 하위 클래스는 DB와 prototype 사이의 실제 변환을 다음 두 메서드로 구현한다.
 * <ul>
 *   <li>{@link #loadOnBuffer(Connection, SubmodelElement)} — DB에서 최신 값을 읽어 주어진 버퍼에 채운다.</li>
 *   <li>{@link #saveBuffer(Connection, SubmodelElement)} — 주어진 SubmodelElement의 값을 DB에 저장한다.</li>
 * </ul>
 * 일관성은 이 클래스가 보장한다. 읽기는 캐시의 복사본에 적재한 뒤 모두 성공한 경우에만 캐시를 교체하므로,
 * 적재 도중 실패해도 캐시가 반쯤 바뀐 채 남지 않는다. 쓰기는 하나의 트랜잭션으로 수행하여, 실패하면
 * 하위 클래스가 실행한 모든 SQL 문을 되돌린다.
 *
 * @param <T>	JDBC 접속 설정을 포함하는 이 {@code AssetVariable}의 설정 타입.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractJdbcAssetVariable<T extends AbstractJdbcAssetVariableConfig>
																			extends AbstractAssetVariable<T> {
	private final JdbcProcessor m_jdbc;
	private final Guard m_guard = Guard.create();
	// 레코드가 없는 경우 반복해서 경고를 출력하지 않도록 정의한 flag. 캐시 잠금 안에서만 접근한다.
	private boolean m_noRecordWarningReported = false;

	/**
	 * DBMS에서 최신 값을 읽어 주어진 버퍼를 채운다.
	 * <p>
	 * {@code buffer}는 캐시({@code m_prototype})의 복사본이며, 이 메소드가 정상 반환한 경우에만 새 캐시가 된다.
	 * 구현은 {@code m_prototype}이 아니라 {@code buffer}에 값을 채워야 한다.
	 *
	 * @param conn		DBMS와 연결된 Connection 객체.
	 * @param buffer	값을 채울 SubmodelElement (캐시의 복사본).
	 * @throws AssetVariableException	JDBC를 통한 읽기나 값 변환이 실패한 경우.
	 */
	abstract protected void loadOnBuffer(Connection conn, SubmodelElement buffer) throws AssetVariableException;

	/**
	 * 주어진 SubmodelElement의 값을 DBMS에 저장한다.
	 * <p>
	 * 저장이 성공하기 전에 캐시가 바뀌지 않도록, 구현은 {@code m_prototype}이 아니라
	 * 인자로 받은 {@code value}의 값을 저장해야 하며 {@code value}를 변경해서는 안 된다.
	 * <p>
	 * 호출자가 트랜잭션(auto-commit 해제)을 열고 이 메소드가 정상 반환하면 commit, 예외를 던지면 rollback한다.
	 * 구현은 commit/rollback을 직접 호출하지 않는다. 갱신해야 할 행이 없어 실제로 반영되지 않은 경우
	 * (예: 영향받은 행 수가 0)에는 {@link AssetVariableException}을 던져야 한다.
	 *
	 * @param conn	 DBMS와 연결된 Connection 객체
	 * @param value	저장할 SubmodelElement.
	 * @throws AssetVariableException	 JDBC를 통한 저장이 실패한 경우.
	 */
	abstract protected void saveBuffer(Connection conn, SubmodelElement value) throws AssetVariableException;
	
	/**
	 * 주어진 설정으로 JDBC 기반 {@code AssetVariable}을 생성한다.
	 *
	 * @param config	JDBC 접속 설정을 포함하는 설정 객체.
	 */
	public AbstractJdbcAssetVariable(T config) {
		super(config);
		setLogger(LoggerFactory.getLogger(getClass()));

		JdbcConfiguration jdbcConf = config.getJdbcConfig();
		m_jdbc = JdbcProcessor.builder(jdbcConf).pooled().build();
	}

	/**
	 * 연결된 DBMS에서 prototype SubmodelElement를 읽어 반환한다.
	 * <p>
	 * 캐시가 만료된 경우에만 {@link #loadOnBuffer(Connection)}를 통해 DB에서 다시 읽으며,
	 * 그렇지 않으면 캐시된 prototype을 사용한다.
	 * <p>
	 * 반환값은 {@link Guard}로 보호되는 임계 구역 안에서 만든 prototype의 복사본이므로,
	 * 호출자가 변경하거나 보관해도 캐시에 영향을 주지 않는다.
	 *
	 * @return prototype SubmodelElement의 복사본.
	 * @throws AssetVariableException	읽기가 불가능하거나({@link #isReadable()}이 {@code false})
	 * 									DB 읽기가 실패한 경우.
	 */
	@Override
	public SubmodelElement read() {
		if ( !isReadable() ) {
			throw new AssetVariableException("This AssetVariable is not readable");
		}
		// 공유 캐시인 m_prototype의 적재/갱신을 직렬화하기 위해 Guard로 임계 구역을 보호한다.
		// 캐시 자체를 반환하면 잠금을 푼 뒤 다른 쓰레드의 적재와 겹치므로 임계 구역 안에서 복사본을 만든다.
		m_guard.lock();
		try {
			Instant now = Instant.now();
			if ( isExpired(now) ) {
				try ( Connection conn = connect() ) {
					// 적재 도중 실패해도 캐시가 반쯤 바뀐 채 남지 않도록, 복사본에 적재하고 성공하면 교체한다.
					SubmodelElement buffer = SubmodelUtils.duplicate(m_prototype);
					loadOnBuffer(conn, buffer);
					m_prototype = buffer;
					
					// Database에서 새로 읽었기 때문에 refresh-time을 갱신한다.
					setLastRefreshTime(now);
				}
				catch ( SQLException e ) {
					String msg = String.format("Failed to read %s", this);
					throw new AssetVariableException(msg, e);
				}
			}

			return SubmodelUtils.duplicate(m_prototype);
		}
		finally {
			m_guard.unlock();
		}
	}

	/**
	 * 주어진 SubmodelElement 값을 연결된 DBMS에 저장한다.
	 * <p>
	 * {@link #saveBuffer(Connection, SubmodelElement)}를 하나의 트랜잭션으로 실행하여 저장하며,
	 * 호출은 {@link Guard}로 직렬화된다. 저장 중 실패하면 트랜잭션을 되돌린다.
	 * 캐시({@code m_prototype})는 저장이 성공한 경우에만 갱신되며, 캐시에는 {@code sme}의 복사본이
	 * 보관되므로 호출자는 호출 이후에도 {@code sme}를 계속 사용할 수 있다.
	 * 저장이 실패하면 캐시를 만료시켜 다음 {@link #read()}에서 DB의 값을 다시 읽도록 한다.
	 *
	 * @param sme	저장할 SubmodelElement.
	 * @throws AssetVariableException	갱신이 불가능하거나({@link #isUpdatable()}이 {@code false})
	 * 									DB 저장이 실패한 경우.
	 */
	@Override
	public void update(SubmodelElement sme) {
		Preconditions.checkNotNullArgument(sme, "sme is null");
		if ( !isUpdatable() ) {
			throw new AssetVariableException("This AssetVariable is not updatable");
		}

		m_guard.lock();
		try ( Connection conn = connect() ) {
			// DB 저장이 성공하기 전에는 캐시를 바꾸지 않는다.
			saveInTransaction(conn, sme);

			// 호출자의 객체를 그대로 캐시로 쓰면 이후 DB에서 다시 읽을 때(loadOnBuffer) 호출자의 객체
			// (예: base persistence가 보관하는 SubmodelElement)가 바뀌므로 복사본을 캐시로 삼는다.
			m_prototype = SubmodelUtils.duplicate(sme);
			setLastRefreshTime(Instant.now());
		}
		catch ( SQLException e ) {
			invalidateCache();
			String msg = String.format("Failed to update %s", this);
			throw new AssetVariableException(msg, e);
		}
		catch ( RuntimeException e ) {
			invalidateCache();
			throw e;
		}
		finally {
			m_guard.unlock();
		}
	}

	/**
	 * 주어진 하위 요소의 값만 바꿔 저장한다.
	 * <p>
	 * 읽기-수정-저장 전체를 {@link Guard}로 보호되는 하나의 임계 구역에서 수행하므로, 같은 변수에 대한
	 * 다른 갱신({@link #update(SubmodelElement)}나 다른 부분 갱신)이 그 사이에 끼어들어 사라지지 않는다.
	 *
	 * @param relPath	하위 요소의 상대 idShort 경로.
	 * @param part		하위 요소에 반영할 값을 가진 SubmodelElement.
	 * @throws AssetVariableException	읽기 또는 저장이 실패한 경우.
	 */
	@Override
	public void updatePart(String relPath, SubmodelElement part) {
		Preconditions.checkNotNullArgument(relPath, "relPath is null");
		Preconditions.checkNotNullArgument(part, "part is null");
		if ( !isUpdatable() ) {
			throw new AssetVariableException("This AssetVariable is not updatable");
		}

		// Guard는 재진입이 가능하므로 read()/update()를 그대로 호출해도 임계 구역이 유지된다.
		m_guard.lock();
		try {
			SubmodelElement whole = read();
			ElementValues.update(SubmodelUtils.traverse(whole, relPath), ElementValues.getValue(part));
			update(whole);
		}
		finally {
			m_guard.unlock();
		}
	}

	/**
	 * DBMS에 대한 새 Connection을 연다.
	 *
	 * @return	Connection 객체. 호출자가 닫는다.
	 * @throws SQLException	접속이 실패한 경우.
	 */
	/**
	 * 조회 결과에 행이 있을 때 {@link #loadOnBuffer}에서 호출한다.
	 * <p>
	 * 이후 다시 행이 없어지면 {@link NoRecordPolicy#KEEP}의 경고를 다시 남기도록 한다.
	 */
	protected void onRecordFound() {
		m_noRecordWarningReported = false;
	}

	/**
	 * 조회 결과에 행이 없을 때 {@link #loadOnBuffer}에서 호출하여 주어진 정책대로 처리한다.
	 * <ul>
	 *   <li>{@link NoRecordPolicy#KEEP} — 버퍼를 바꾸지 않는다. 경고는 행이 없어진 첫 조회에서만 남긴다.</li>
	 *   <li>{@link NoRecordPolicy#CLEAR} — {@code clearer}로 버퍼의 값을 비운다.</li>
	 *   <li>{@link NoRecordPolicy#FAIL} — {@link AssetVariableException}을 던진다.</li>
	 * </ul>
	 *
	 * @param policy	적용할 정책.
	 * @param query		실행한 조회 질의(메시지용).
	 * @param clearer	버퍼의 값을 비우는 작업.
	 * @throws AssetVariableException	정책이 {@link NoRecordPolicy#FAIL}인 경우.
	 */
	protected void handleNoRecord(NoRecordPolicy policy, String query, Runnable clearer) {
		switch ( policy ) {
			case FAIL:
				throw new AssetVariableException(String.format("No record for %s with query: %s", this, query));
			case CLEAR:
				clearer.run();
				break;
			case KEEP:
			default:
				if ( !m_noRecordWarningReported ) {
					getLogger().warn("No record for {}, keeping the previous value: query={}", this, query);
					m_noRecordWarningReported = true;
				}
				break;
		}
	}

	protected Connection connect() throws SQLException {
		return m_jdbc.connect();
	}

	/**
	 * {@link #saveBuffer(Connection, SubmodelElement)}를 하나의 트랜잭션으로 실행한다.
	 * 성공하면 commit, 실패하면 rollback하며, 원래의 auto-commit 설정을 복원한다.
	 */
	private void saveInTransaction(Connection conn, SubmodelElement sme) throws SQLException {
		boolean autoCommit = conn.getAutoCommit();
		conn.setAutoCommit(false);
		try {
			saveBuffer(conn, sme);
			conn.commit();
		}
		catch ( SQLException | RuntimeException e ) {
			try {
				conn.rollback();
			}
			catch ( SQLException re ) {
				e.addSuppressed(re);
			}
			throw e;
		}
		finally {
			try {
				conn.setAutoCommit(autoCommit);
			}
			catch ( SQLException ignored ) {
				// 곧 닫을 Connection이므로 복원 실패는 무시한다.
			}
		}
	}

	/**
	 * 캐시를 만료시켜 다음 {@link #read()}에서 DB의 값을 다시 읽도록 한다.
	 * <p>
	 * 저장에 실패했을 때 호출한다. 저장은 트랜잭션으로 되돌리지만, commit 결과를 확인할 수 없는 경우
	 * (예: commit 도중 연결이 끊긴 경우)도 있으므로 캐시를 그대로 두지 않고 다음 읽기에서 DB의 실제 값을 다시 읽는다.
	 */
	private void invalidateCache() {
		// 마지막 갱신 시각의 초기값(EPOCH)은 '한번도 읽지 않음'을 뜻한다.
		setLastRefreshTime(Instant.EPOCH);
	}
	
	@Override
	public String toString() {
		return String.format("%s[%s, jdbc=%s]",
							getClass().getSimpleName(), getElementLocation(),
							getConfig().getJdbcConfig());
	}
}
