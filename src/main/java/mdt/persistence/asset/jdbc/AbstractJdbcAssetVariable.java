package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.LoggerFactory;

import utils.jdbc.JdbcConfiguration;
import utils.jdbc.JdbcProcessor;
import utils.thread.Guard;

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
 *   <li>{@link #saveBuffer(Connection)} — {@code m_prototype}의 값을 DB에 저장한다.</li>
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
	 * 갱신된 prototype을 DBMS에 저장한다.
	 * 
	 * @param conn	 DBMS와 연결된 Connection 객체
	 * @throws AssetVariableException	 JDBC를 통한 저장이 실패한 경우.
	 */
	abstract protected void saveBuffer(Connection conn) throws AssetVariableException;
	
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
	 * 그렇지 않으면 캐시된 prototype을 반환한다.
	 * <p>
	 * <b>주의:</b> 반환값은 공유되는 캐시 인스턴스인 {@code m_prototype} 그 자체이다.
	 * {@link Guard}는 메서드 내부의 임계 구역만 직렬화할 뿐 반환된 참조까지 보호하지 못하므로,
	 * 호출자는 반환값을 변경하거나 다음 호출 이후까지 보유해서는 안 된다(필요하면 복사본을 사용).
	 *
	 * @return prototype SubmodelElement (공유 캐시 인스턴스).
	 * @throws AssetVariableException	읽기가 불가능하거나({@link #isReadable()}이 {@code false})
	 * 									DB 읽기가 실패한 경우.
	 */
	@Override
	public SubmodelElement read() {
		if ( !isReadable() ) {
			throw new AssetVariableException("This AssetVariable is not readable");
		}
		// 공유 캐시인 m_prototype의 적재/갱신을 직렬화하기 위해 Guard로 임계 구역을 보호한다.
		// (반환된 참조 자체는 보호 대상이 아니므로 read()의 계약 참조)
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
			
			return m_prototype;
		}
		finally {
			m_guard.unlock();
		}
	}

	/**
	 * 주어진 SubmodelElement 값을 연결된 DBMS에 저장한다.
	 * <p>
	 * {@link #saveBuffer(Connection)}를 통해 저장하며, 호출은 {@link Guard}로 직렬화된다.
	 * <p>
	 * <b>주의:</b> 전달된 {@code sme}는 내부 캐시({@code m_prototype})로 채택되어 보유될 수 있다.
	 * 따라서 호출자는 이 메서드 호출 이후 해당 객체를 변경해서는 안 된다.
	 *
	 * @param sme	저장할 SubmodelElement.
	 * @throws AssetVariableException	갱신이 불가능하거나({@link #isUpdatable()}이 {@code false})
	 * 									DB 저장이 실패한 경우.
	 */
	@Override
	public void update(SubmodelElement sme) {
		if ( !isUpdatable() ) {
			throw new AssetVariableException("This AssetVariable is not updatable");
		}
		
		m_guard.lock();
		try ( Connection conn = m_jdbc.connect() ) {
			if ( sme != m_prototype ) {
				m_prototype = sme;
			}
			saveBuffer(conn);

			setLastRefreshTime(Instant.now());
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
}
