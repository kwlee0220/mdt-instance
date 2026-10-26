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
 *   <li>{@link #loadOnBuffer(Connection)} — DB에서 최신 값을 읽어 {@code m_prototype}에 채운다.</li>
 *   <li>{@link #saveBuffer(Connection, SubmodelElement)} — 주어진 SubmodelElement의 값을 DB에 저장한다.</li>
 * </ul>
 *
 * @param <T>	JDBC 접속 설정을 포함하는 이 {@code AssetVariable}의 설정 타입.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractJdbcAssetVariable<T extends AbstractJdbcAssetVariableConfig>
																			extends AbstractAssetVariable<T> {
	private final JdbcProcessor m_jdbc;
	private final Guard m_guard = Guard.create();

	/**
	 * DBMS에서 최신 값을 읽어 prototype({@code m_prototype}) 버퍼를 채운다.
	 *
	 * @param conn	DBMS와 연결된 Connection 객체.
	 * @throws AssetVariableException	JDBC를 통한 읽기가 실패한 경우.
	 */
	abstract protected void loadOnBuffer(Connection conn) throws AssetVariableException;

	/**
	 * 주어진 SubmodelElement의 값을 DBMS에 저장한다.
	 * <p>
	 * 저장이 성공하기 전에 캐시가 바뀌지 않도록, 구현은 {@code m_prototype}이 아니라
	 * 인자로 받은 {@code value}의 값을 저장해야 하며 {@code value}를 변경해서는 안 된다.
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
		m_jdbc = JdbcProcessor.builder(jdbcConf).build();
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
				try ( Connection conn = m_jdbc.connect() ) {
					loadOnBuffer(conn);
					
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
	 * {@link #saveBuffer(Connection, SubmodelElement)}를 통해 저장하며, 호출은 {@link Guard}로 직렬화된다.
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
		try ( Connection conn = m_jdbc.connect() ) {
			// DB 저장이 성공하기 전에는 캐시를 바꾸지 않는다.
			saveBuffer(conn, sme);

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
	 * 캐시를 만료시켜 다음 {@link #read()}에서 DB의 값을 다시 읽도록 한다.
	 * <p>
	 * 저장에 실패했을 때 호출한다. 하위 클래스에 따라 저장이 여러 SQL 문으로 이루어지므로
	 * (예: {@link MultiRowAssetVariable}) 실패 시 DB에 일부만 반영되었을 수 있다. 따라서 캐시를
	 * 그대로 두지 않고 다음 읽기에서 DB의 실제 값을 다시 읽는다.
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
