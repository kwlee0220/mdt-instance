package mdt.persistence.asset.jdbc;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementCollection;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.json.JsonMapper;

import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;

/**
 * JDBC AssetVariable의 캐시·트랜잭션 일관성을 가짜 JDBC 객체로 검증한다.
 * <ul>
 *   <li>적재 도중 실패해도 캐시가 반쯤 바뀐 채 남지 않는다.</li>
 *   <li>한 행도 바꾸지 못한 갱신은 실패로 처리하고 되돌린다.</li>
 *   <li>여러 SQL 문으로 이루어진 갱신은 하나의 트랜잭션이다.</li>
 * </ul>
 */
public class JdbcAssetVariableConsistencyTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Map<String,Object> JDBC = Map.of("jdbcUrl", "jdbc:postgresql://localhost/test",
															"user", "user", "password", "secret");

	private MDTModelLookup lookup;
	private Connection conn;
	private Statement stmt;
	private ResultSet rs;
	private PreparedStatement pstmt;

	@Before
	public void setUp() throws Exception {
		Submodel data = new DefaultSubmodel.Builder().idShort("Data").id("urn:test:data")
				.submodelElements(new ArrayList<>(List.of(
						property("Speed", "0"),
						collection("Pose", property("X", "0"), property("Y", "0")),
						collection("Params", property("A", "0"), property("B", "0")))))
				.build();
		lookup = mock(MDTModelLookup.class);
		when(lookup.getSubmodelByIdShort("Data")).thenReturn(data);

		conn = mock(Connection.class);
		stmt = mock(Statement.class);
		rs = mock(ResultSet.class);
		pstmt = mock(PreparedStatement.class);
		when(conn.getAutoCommit()).thenReturn(true);
		when(conn.createStatement()).thenReturn(stmt);
		when(stmt.executeQuery(anyString())).thenReturn(rs);
		when(conn.prepareStatement(anyString())).thenReturn(pstmt);
	}

	// ---------- 적재 실패 시 캐시 보존 ----------

	@Test
	public void multiColumnLoadFailureLeavesCacheUnchanged() throws Exception {
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of());
		when(rs.next()).thenReturn(true, true, false);
		when(rs.getObject(1)).thenReturn(1.0, 10.0);		// X
		when(rs.getObject(2)).thenReturn(2.0, "bad");		// Y

		SubmodelElement first = var.read();
		assertEquals(1.0, value(first, "X"), 0);
		assertEquals(2.0, value(first, "Y"), 0);

		// X는 변환되고 Y에서 실패한다.
		assertThrows(AssetVariableException.class, var::read);

		// 다음 조회는 레코드가 없어 캐시를 그대로 돌려준다. 실패한 조회의 X가 섞이면 안 된다.
		SubmodelElement third = var.read();
		assertEquals(1.0, value(third, "X"), 0);
		assertEquals(2.0, value(third, "Y"), 0);
	}

	@Test
	public void multiRowLoadFailureLeavesCacheUnchanged() throws Exception {
		MultiRowAssetVariable var = multiRow(null);
		// 조회1: 두 행, 조회2: 두 번째 행에서 변환 실패, 조회3: 행 없음
		when(rs.next()).thenReturn(true, true, false, true, true, false);
		when(rs.getString(1)).thenReturn("A", "B", "A", "B");
		when(rs.getObject(2)).thenReturn(1.0, 2.0, 10.0, "bad");

		SubmodelElement first = var.read();
		assertEquals(1.0, value(first, "A"), 0);

		assertThrows(AssetVariableException.class, var::read);

		SubmodelElement third = var.read();
		assertEquals(1.0, value(third, "A"), 0);
		assertEquals(2.0, value(third, "B"), 0);
	}

	// ---------- 0행 갱신은 실패, 트랜잭션 ----------

	@Test
	public void simpleUpdateOfNoRowFailsAndRollsBack() throws Exception {
		SimpleJdbcAssetVariable var = simple();
		when(pstmt.executeUpdate()).thenReturn(0);

		AssetVariableException e = assertThrows(AssetVariableException.class, () -> var.update(property("Speed", "5")));
		assertTrue(e.getMessage(), e.getMessage().startsWith("No row was updated"));
		verify(conn).setAutoCommit(false);
		verify(conn).rollback();
		verify(conn, never()).commit();
		verify(conn).setAutoCommit(true);

		// 캐시가 무효화되어 DB의 값을 다시 읽는다.
		when(rs.next()).thenReturn(true);
		when(rs.getObject(1)).thenReturn(3.0);
		assertEquals("3.0", ((Property)var.read()).getValue());
	}

	@Test
	public void simpleUpdateCommitsAndCachesValue() throws Exception {
		SimpleJdbcAssetVariable var = simple();
		when(pstmt.executeUpdate()).thenReturn(1);

		var.update(property("Speed", "5"));
		verify(conn).commit();
		verify(conn, never()).rollback();

		// 유효 기간 안이므로 DB를 다시 읽지 않고 저장한 값을 돌려준다.
		assertEquals("5", ((Property)var.read()).getValue());
		verify(stmt, never()).executeQuery(anyString());
	}

	@Test
	public void multiColumnUpdateModeOfNoRowFailsAndRollsBack() throws Exception {
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("updateMode", "UPDATE"));
		when(pstmt.executeUpdate()).thenReturn(0);

		assertThrows(AssetVariableException.class,
					() -> var.update(collection("Pose", property("X", "1"), property("Y", "2"))));
		verify(conn).rollback();
		verify(conn, never()).commit();
	}

	@Test
	public void multiRowUpdateIsOneTransaction() throws Exception {
		MultiRowAssetVariable var = multiRow("update t set v = ? where k = ?");
		SubmodelElementCollection params = collection("Params", property("A", "1"), property("B", "2"));

		// 두 번째 key의 행이 없으면, 앞서 갱신한 첫 번째 행까지 되돌린다.
		when(pstmt.executeUpdate()).thenReturn(1, 0);
		AssetVariableException e = assertThrows(AssetVariableException.class, () -> var.update(params));
		assertTrue(e.getMessage(), e.getMessage().contains("No row was updated"));
		verify(pstmt, times(2)).executeUpdate();
		verify(conn).rollback();
		verify(conn, never()).commit();

		reset(pstmt);
		when(pstmt.executeUpdate()).thenReturn(1);
		var.update(params);
		verify(pstmt, times(2)).executeUpdate();
		verify(conn).commit();
	}

	// ---------- helpers ----------

	private SimpleJdbcAssetVariable simple() throws Exception {
		SimpleJdbcAssetVariableConfig config = SimpleJdbcAssetVariableConfig.deserializeFields(JSON.valueToTree(Map.of(
				"element", "Data:Speed", "jdbcConfig", JDBC, "validPeriod", "1h",
				"readQuery", "select v from t", "updateQuery", "update t set v = ?")));
		SimpleJdbcAssetVariable var = new SimpleJdbcAssetVariable(config) {
			@Override protected Connection connect() { return conn; }
		};
		var.initialize(lookup);
		return var;
	}

	private MultiColumnCollectionAssetVariable multiColumn(Map<String,Object> extra) throws Exception {
		Map<String,Object> fields = new HashMap<>(Map.of(
				"element", "Data:Pose", "jdbcConfig", JDBC, "table", "t", "whereClause", "where id = 1",
				"columns", List.of(Map.of("column", "X", "subPath", "X"), Map.of("column", "Y", "subPath", "Y"))));
		fields.putAll(extra);
		MultiColumnCollectionAssetVariableConfig config
								= MultiColumnCollectionAssetVariableConfig.deserializeFields(JSON.valueToTree(fields));
		MultiColumnCollectionAssetVariable var = new MultiColumnCollectionAssetVariable(config) {
			@Override protected Connection connect() { return conn; }
		};
		var.initialize(lookup);
		return var;
	}

	private MultiRowAssetVariable multiRow(String updateQuery) throws Exception {
		Map<String,Object> fields = new HashMap<>(Map.of(
				"element", "Data:Params", "jdbcConfig", JDBC, "readQuery", "select k, v from t",
				"rows", List.of(Map.of("key", "A", "subPath", "A"), Map.of("key", "B", "subPath", "B"))));
		if ( updateQuery != null ) {
			fields.put("updateQuery", updateQuery);
		}
		MultiRowAssetVariableConfig config = MultiRowAssetVariableConfig.deserializeFields(JSON.valueToTree(fields));
		MultiRowAssetVariable var = new MultiRowAssetVariable(config) {
			@Override protected Connection connect() { return conn; }
		};
		var.initialize(lookup);
		return var;
	}

	private static double value(SubmodelElement smc, String idShort) {
		return ((SubmodelElementCollection)smc).getValue().stream().filter(e -> idShort.equals(e.getIdShort()))
				.map(e -> Double.parseDouble(((Property)e).getValue())).findFirst().orElseThrow();
	}

	private static Property property(String idShort, String value) {
		return new DefaultProperty.Builder().idShort(idShort).valueType(DataTypeDefXsd.DOUBLE).value(value).build();
	}

	private static SubmodelElementCollection collection(String idShort, SubmodelElement... members) {
		return new DefaultSubmodelElementCollection.Builder().idShort(idShort).value(new ArrayList<>(List.of(members))).build();
	}
}
