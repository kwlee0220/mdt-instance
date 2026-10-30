package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.LoggerFactory;

import utils.Preconditions;
import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;
import utils.jdbc.JdbcUtils;
import utils.stream.FStream;
import utils.thread.Guard;

import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.asset.AbstractAssetVariable;
import mdt.persistence.asset.AssetVariable;
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
	private final String m_jdbcDisplay;		// 로그용 접속 정보 (비밀번호를 가린 URL 또는 전역 설정 이름)
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

		// toString()이 로그와 예외 메시지에 쓰이므로, 전역 설정 파일을 다시 읽지 않도록 미리 만들어 두고
		// JDBC URL에 포함된 비밀번호는 가린다.
		m_jdbcDisplay = config.getJdbcConfigRef()
								.toDisplayString("jdbcs", cfg -> JdbcUtils.maskPassword(cfg.getJdbcUrl()));
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
		try {
			saveAndCache(sme, conn -> saveBuffer(conn, sme));
		}
		finally {
			m_guard.unlock();
		}
	}

	/**
	 * 주어진 하위 요소의 값만 바꿔 저장한다.
	 * <p>
	 * 캐시의 유효 기간과 관계없이 DB에서 최신 값을 다시 읽어 하위 요소의 값을 바꾼 뒤
	 * {@link #savePart(Connection, SubmodelElement, String)}로 저장한다. 캐시를 쓰면 그 사이 다른 프로그램이
	 * DB에 기록한 값을 캐시의 이전 값으로 덮어쓰게 되기 때문이다.
	 * 기본 구현은 전체를 저장하며, 컬럼이나 행 단위로 나누어 저장할 수 있는 구현은 바뀐 부분만 저장하도록
	 * {@code savePart}를 재정의한다.
	 * <p>
	 * 읽기-수정-저장 전체를 {@link Guard}로 보호되는 하나의 임계 구역에서 수행하므로, 같은 변수에 대한
	 * 다른 갱신({@link #update(SubmodelElement)}나 다른 부분 갱신)이 그 사이에 끼어들어 사라지지 않는다.
	 * 다만 다른 프로그램이 다시 읽기와 저장 사이에 같은 값을 바꾸는 경쟁은 막지 않는다.
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

		m_guard.lock();
		try {
			SubmodelElement whole = (isReadable()) ? loadLatest() : SubmodelUtils.duplicate(m_prototype);
			ElementValues.update(SubmodelUtils.traverse(whole, relPath), ElementValues.getValue(part));
			saveAndCache(whole, conn -> savePart(conn, whole, relPath));
		}
		finally {
			m_guard.unlock();
		}
	}
	
	/**
	 * {@link #updatePart(String, SubmodelElement)}에서 하위 요소의 값이 바뀐 전체 값을 DBMS에 저장한다.
	 * <p>
	 * {@code whole}은 DB에서 다시 읽은 최신 값에 {@code relPath}의 하위 요소 값을 반영한 것이다.
	 * 기본 구현은 {@link #saveBuffer(Connection, SubmodelElement)}로 전체를 저장한다. 컬럼이나 행 단위로
	 * 저장할 수 있는 구현은 {@code relPath}와 관련된 부분만 저장하도록 재정의하여, 다른 프로그램이 바꾼
	 * 다른 컬럼·행의 값을 덮어쓰지 않게 한다. 호출 규칙(트랜잭션, 예외)은 {@code saveBuffer}와 같다.
	 *
	 * @param conn		DBMS와 연결된 Connection 객체.
	 * @param whole		저장할 전체 값.
	 * @param relPath	값이 바뀐 하위 요소의 상대 경로.
	 * @throws AssetVariableException	저장이 실패한 경우.
	 */
	protected void savePart(Connection conn, SubmodelElement whole, String relPath) throws AssetVariableException {
		saveBuffer(conn, whole);
	}
	
	/**
	 * 매핑된 하위 경로 {@code subPath}의 값이 하위 요소 {@code relPath}의 갱신으로 바뀌는지 반환한다.
	 * <p>
	 * 두 경로 중 하나가 다른 하나를 포함하면 겹친다고 본다. 빈 {@code subPath}는 요소 전체를 뜻한다.
	 *
	 * @param subPath	매핑된 하위 경로.
	 * @param relPath	갱신된 하위 요소의 상대 경로.
	 * @return	겹치면 {@code true}.
	 */
	protected static boolean overlaps(String subPath, String relPath) {
		return subPath.isEmpty() || relPath.isEmpty()
				|| AssetVariable.isPathPrefix(subPath, relPath) || AssetVariable.isPathPrefix(relPath, subPath);
	}
	
	/**
	 * 캐시의 유효 기간과 관계없이 DB에서 최신 값을 읽어 캐시를 갱신하고, 그 복사본을 반환한다.
	 * {@link Guard}를 잡은 상태에서 호출한다.
	 */
	private SubmodelElement loadLatest() {
		Instant now = Instant.now();
		try ( Connection conn = connect() ) {
			SubmodelElement buffer = SubmodelUtils.duplicate(m_prototype);
			loadOnBuffer(conn, buffer);
			m_prototype = buffer;
			setLastRefreshTime(now);
			return SubmodelUtils.duplicate(buffer);
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read %s", this);
			throw new AssetVariableException(msg, e);
		}
	}
	
	/** 하나의 트랜잭션 안에서 실행할 저장 작업. */
	@FunctionalInterface
	private interface SaveAction {
		void save(Connection conn) throws SQLException;
	}
	
	/**
	 * {@code action}을 하나의 트랜잭션으로 실행하고, 성공하면 {@code value}의 복사본을 캐시로 삼는다.
	 * 실패하면 캐시를 만료시킨다. {@link Guard}를 잡은 상태에서 호출한다.
	 */
	private void saveAndCache(SubmodelElement value, SaveAction action) {
		try ( Connection conn = connect() ) {
			// DB 저장이 성공하기 전에는 캐시를 바꾸지 않는다.
			saveInTransaction(conn, action);

			// 호출자의 객체를 그대로 캐시로 쓰면 이후 DB에서 다시 읽을 때(loadOnBuffer) 호출자의 객체
			// (예: base persistence가 보관하는 SubmodelElement)가 바뀌므로 복사본을 캐시로 삼는다.
			m_prototype = SubmodelUtils.duplicate(value);
			if ( isReloadNeededAfterSave() ) {
				// 저장한 값과 DB의 값이 다를 수 있으므로(읽기 전용·계산 컬럼 등) 다음 읽기에서 다시 조회한다.
				invalidateCache();
			}
			else {
				setLastRefreshTime(Instant.now());
			}
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
	}

	/**
	 * 저장에 성공한 뒤 캐시를 만료시켜 다음 {@link #read()}에서 DB의 값을 다시 읽어야 하는지 반환한다.
	 * <p>
	 * 기본값은 {@code false}로, 저장한 값을 그대로 캐시로 삼는다. 저장한 값의 일부가 DB에 기록되지 않거나
	 * DB가 값을 계산하는 구현(예: 읽기 전용 매핑이 있는 경우)은 {@code true}를 반환하여, 저장 요청의 값이
	 * 아닌 DB의 실제 값이 반환되도록 한다.
	 *
	 * @return	저장 후 다시 읽어야 하면 {@code true}.
	 */
	protected boolean isReloadNeededAfterSave() {
		return false;
	}

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

	/**
	 * 조회 결과가 없을 때 값을 비우는 정책({@link NoRecordPolicy#CLEAR})을 모든 바인딩에 적용할 수 있는지 확인한다.
	 * <p>
	 * JSON으로 주고받는 요소는 비울 수 없으므로, 그런 요소가 있으면 {@code CLEAR}가 일부 요소에만 적용되어
	 * 값이 섞이게 된다. 이를 시작 단계에서 거부한다. {@code initialize()}에서 바인딩을 만든 뒤 호출한다.
	 *
	 * @param policy	설정된 정책.
	 * @param bindings	이 변수의 바인딩들.
	 * @throws AssetVariableException	{@code CLEAR}인데 비울 수 없는 요소가 있는 경우.
	 */
	protected void checkNoRecordPolicy(NoRecordPolicy policy, Collection<JdbcColumnBinding> bindings) {
		if ( policy != NoRecordPolicy.CLEAR ) {
			return;
		}
		List<String> unclearable = FStream.from(bindings)
										.filter(binding -> !binding.isClearable())
										.map(binding -> binding.getSubPath().isEmpty() ? "(element)" : binding.getSubPath())
										.toList();
		if ( !unclearable.isEmpty() ) {
			throw new AssetVariableException(String.format(
					"'onNoRecord: CLEAR' cannot clear elements exchanged as JSON (use KEEP or FAIL): %s, subPaths=%s",
					this, unclearable));
		}
	}

	/**
	 * DBMS와의 Connection을 연결 풀에서 얻는다.
	 *
	 * @return	Connection 객체. 호출자가 닫는다.
	 * @throws SQLException	접속이 실패한 경우.
	 */
	protected Connection connect() throws SQLException {
		return m_jdbc.connect();
	}
	
	/**
	 * 설정의 실행 제한 시간({@code queryTimeout})을 적용한 {@link Statement}를 만든다.
	 * <p>
	 * 이 변수의 읽기·쓰기는 잠금을 잡은 채 SQL을 실행하므로, 실행이 끝나지 않으면 같은 변수의 다른 요청도
	 * 함께 기다린다. 제한 시간을 넘기면 드라이버가 실행을 취소하고 {@link SQLException}을 던진다.
	 * 연결 풀에서 연결을 얻는 대기 시간과는 별개다.
	 *
	 * @param conn	Connection 객체.
	 * @return	Statement 객체. 호출자가 닫는다.
	 * @throws SQLException	생성이 실패한 경우.
	 */
	protected Statement createStatement(Connection conn) throws SQLException {
		Statement stmt = conn.createStatement();
		stmt.setQueryTimeout(getQueryTimeoutSeconds());
		return stmt;
	}
	
	/**
	 * 설정의 실행 제한 시간({@code queryTimeout})을 적용한 {@link PreparedStatement}를 만든다.
	 *
	 * @param conn	Connection 객체.
	 * @param sql	SQL 문.
	 * @return	PreparedStatement 객체. 호출자가 닫는다.
	 * @throws SQLException	생성이 실패한 경우.
	 * @see #createStatement(Connection)
	 */
	protected PreparedStatement prepareStatement(Connection conn, String sql) throws SQLException {
		PreparedStatement pstmt = conn.prepareStatement(sql);
		pstmt.setQueryTimeout(getQueryTimeoutSeconds());
		return pstmt;
	}
	
	private int getQueryTimeoutSeconds() {
		// JDBC의 제한 시간은 초 단위이며 0은 '제한 없음'이므로 1초 미만은 1초로 올린다.
		long seconds = getConfig().getQueryTimeout().toSeconds();
		return (int)Math.max(1, Math.min(seconds, Integer.MAX_VALUE));
	}

	/**
	 * 저장 작업을 하나의 트랜잭션으로 실행한다.
	 * 성공하면 commit, 실패하면 rollback하며, 원래의 auto-commit 설정을 복원한다.
	 */
	private void saveInTransaction(Connection conn, SaveAction action) throws SQLException {
		boolean autoCommit = conn.getAutoCommit();
		conn.setAutoCommit(false);
		try {
			action.save(conn);
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
		return String.format("%s[%s, jdbc=%s]", getClass().getSimpleName(), getElementLocation(), m_jdbcDisplay);
	}
}
