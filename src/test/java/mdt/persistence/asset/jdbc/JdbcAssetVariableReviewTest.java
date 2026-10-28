package mdt.persistence.asset.jdbc;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementList;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.json.JsonMapper;

import utils.json.JacksonDeserializationException;

import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;


/**
 * JDBC AssetVariable 검토 의견에 대한 수정을 가짜 JDBC 객체로 검증한다.
 * <ul>
 *   <li>UPDATE 범위 검증과 갱신 건수 검사</li>
 *   <li>로그·예외 메시지의 비밀번호 마스킹</li>
 *   <li>부분 갱신에서 최신 값 재조회와 컬럼·key 단위 저장</li>
 *   <li>템플릿 없는 목록의 DECIMAL 정밀도, 매핑 목록 불변성</li>
 *   <li>SQL 실행 제한 시간, 비울 수 없는 요소와 CLEAR, writeColumn·readOnly</li>
 * </ul>
 */
@SuppressWarnings("resource")
public class JdbcAssetVariableReviewTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Map<String,Object> JDBC = Map.of("jdbcUrl", "jdbc:postgresql://localhost/test",
															"user", "user", "password", "secret");
	private static final List<Map<String,String>> POSE_COLUMNS
			= List.of(Map.of("column", "X", "subPath", "X"), Map.of("column", "Y", "subPath", "Y"));

	private MDTModelLookup lookup;
	private Connection conn;
	private Statement stmt;
	private ResultSet rs;
	private PreparedStatement pstmt;

	@Before
	public void setUp() throws Exception {
		Submodel data = new DefaultSubmodel.Builder().idShort("Data").id("urn:test:data")
				.submodelElements(new ArrayList<>(List.of(
						collection("Pose", property("X", "0"), property("Y", "0")),
						collection("Params", property("A", "0"), property("B", "0")),
						collection("Info", collection("Detail", property("Z", "0"))),
						new DefaultSubmodelElementList.Builder().idShort("Lots").value(new ArrayList<>()).build())))
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

	// ---------- UPDATE 범위 ----------

	@Test
	public void updateModeRejectsWhereClauseWithoutWhere() {
		for ( String clause: new String[] { "order by id desc limit 1", "/* no predicate */", "-- where id = 1" } ) {
			assertThrows(clause, JacksonDeserializationException.class,
						() -> multiColumnConfig(Map.of("updateMode", "UPDATE", "whereClause", clause)));
		}
		// WHERE 절이 있으면 받는다.
		multiColumnConfig(Map.of("updateMode", "UPDATE", "whereClause", "WHERE id = 1 /* pose */"));
		multiColumnConfig(Map.of("updateMode", "UPDATE", "where", "id = 1"));
	}

	@Test
	public void updateOfMultipleRowsFailsAndRollsBack() throws Exception {
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("updateMode", "UPDATE", "where", "id = 1"));
		when(pstmt.executeUpdate()).thenReturn(2);

		AssetVariableException e = assertThrows(AssetVariableException.class,
									() -> var.update(collection("Pose", property("X", "1"), property("Y", "2"))));
		assertTrue(e.getMessage(), e.getMessage().startsWith("2 rows were updated"));
		verify(conn).rollback();
		verify(conn, never()).commit();
	}

	// ---------- 비밀번호 마스킹 ----------

	@Test
	public void toStringMasksPasswordInJdbcUrl() throws Exception {
		Map<String,Object> jdbc = Map.of("jdbcUrl", "jdbc:postgresql://db/mdt?user=mdt&password=topsecret",
											"user", "mdt", "password", "topsecret");
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("jdbcConfig", jdbc));
		assertFalse(var.toString(), var.toString().contains("topsecret"));
		assertTrue(var.toString(), var.toString().contains("jdbc:postgresql://db/mdt"));
	}

	// ---------- 부분 갱신 ----------

	@Test
	public void partialUpdateRereadsAndWritesOnlyAffectedColumn() throws Exception {
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("updateMode", "UPDATE", "where", "id = 1",
																	"validPeriod", "1h"));
		// 첫 조회: X=1, Y=2 (캐시됨)
		when(rs.next()).thenReturn(true);
		when(rs.getObject(1)).thenReturn(1.0, 1.0);
		when(rs.getObject(2)).thenReturn(2.0, 99.0);		// 다른 프로그램이 Y를 99로 바꿈
		var.read();
		when(pstmt.executeUpdate()).thenReturn(1);

		var.updatePart("X", property("X", "3"));

		// 유효 기간 안이어도 DB를 다시 읽고, X 컬럼만 갱신한다.
		verify(stmt, times(2)).executeQuery(anyString());
		verify(conn).prepareStatement("update t set X = ? where id = 1");
		verify(pstmt).setObject(1, 3.0);
		verify(pstmt, never()).setObject(eq(2), any());
		verify(conn).commit();

		// 캐시에는 다시 읽은 Y(99)와 갱신한 X(3)가 함께 남는다.
		SubmodelElement cached = var.read();
		assertEquals(3.0, Double.parseDouble(value(cached, "X")), 0);
		assertEquals(99.0, Double.parseDouble(value(cached, "Y")), 0);
	}

	@Test
	public void partialUpdateOfUnmappedElementWritesNothing() throws Exception {
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("updateMode", "UPDATE", "where", "id = 1",
																	"columns", List.of(Map.of("column", "X", "subPath", "X"))));
		when(rs.next()).thenReturn(true);
		when(rs.getObject(1)).thenReturn(1.0);

		var.updatePart("Y", property("Y", "5"));
		verify(conn, never()).prepareStatement(anyString());
	}

	@Test
	public void partialUpdateOfAppendModeInsertsWholeRow() throws Exception {
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("updateMode", "APPEND"));
		when(rs.next()).thenReturn(true);
		when(rs.getObject(1)).thenReturn(1.0);
		when(rs.getObject(2)).thenReturn(2.0);
		when(pstmt.executeUpdate()).thenReturn(1);

		var.updatePart("X", property("X", "3"));
		verify(conn).prepareStatement("insert into t (X, Y) values (?, ?)");
		verify(pstmt).setObject(1, 3.0);
		verify(pstmt).setObject(2, 2.0);
	}

	@Test
	public void partialUpdateOfMultiRowWritesOnlyAffectedKey() throws Exception {
		MultiRowAssetVariable var = multiRow("update t set v = ? where k = ?");
		when(rs.next()).thenReturn(true, true, false);
		when(rs.getString(1)).thenReturn("A", "B");
		when(rs.getObject(2)).thenReturn(1.0, 2.0);
		when(pstmt.executeUpdate()).thenReturn(1);

		var.updatePart("B", property("B", "7"));
		verify(pstmt, times(1)).executeUpdate();
		verify(pstmt).setString(2, "B");
		verify(pstmt, never()).setString(2, "A");
	}

	// ---------- DECIMAL, 불변 목록 ----------

	@Test
	public void listWithoutTemplateKeepsDecimalPrecision() throws Exception {
		ResultSetMetaData meta = mock(ResultSetMetaData.class);
		when(rs.getMetaData()).thenReturn(meta);
		when(meta.getColumnCount()).thenReturn(1);
		when(meta.getColumnType(1)).thenReturn(Types.NUMERIC);
		when(rs.next()).thenReturn(true, false);
		when(rs.getObject(1)).thenReturn(new BigDecimal("9007199254740993.01"));

		MultiColumnCollectionListAssetVariableConfig config = MultiColumnCollectionListAssetVariableConfig.deserializeFields(
				JSON.valueToTree(Map.of("element", "Data:Lots", "jdbcConfig", JDBC, "table", "lots",
										"columns", List.of(Map.of("column", "amount", "subPath", "Amount")))));
		MultiColumnCollectionListAssetVariable var = new MultiColumnCollectionListAssetVariable(config) {
			@Override protected Connection connect() { return conn; }
		};
		var.initialize(lookup);

		SubmodelElementCollection row = (SubmodelElementCollection)((SubmodelElementList)var.read()).getValue().get(0);
		Property amount = (Property)row.getValue().get(0);
		assertEquals(DataTypeDefXsd.DECIMAL, amount.getValueType());
		assertEquals("9007199254740993.01", amount.getValue());
	}

	@Test
	public void mappingsCannotBeModified() throws Exception {
		MultiColumnCollectionAssetVariableConfig config = multiColumnConfig(Map.of());
		assertThrows(UnsupportedOperationException.class,
					() -> config.getColumnToSubPathMappings().add(new ColumnToSubPath("Z", "Z")));

		MultiRowAssetVariableConfig rowConfig = multiRow(null).getConfig();
		assertThrows(UnsupportedOperationException.class, () -> rowConfig.getKeyToSubpathMapping().put("C", "C"));
	}

	// ---------- 실행 제한 시간 ----------

	@Test
	public void queryTimeoutIsAppliedToStatements() throws Exception {
		when(rs.next()).thenReturn(true);
		when(pstmt.executeUpdate()).thenReturn(1);

		MultiColumnCollectionAssetVariable dflt = multiColumn(Map.of("updateMode", "UPDATE", "where", "id = 1"));
		dflt.read();
		dflt.update(collection("Pose", property("X", "1"), property("Y", "2")));
		verify(stmt).setQueryTimeout(30);
		verify(pstmt).setQueryTimeout(30);

		reset(stmt);
		when(stmt.executeQuery(anyString())).thenReturn(rs);
		multiColumn(Map.of("queryTimeout", "5s")).read();
		verify(stmt).setQueryTimeout(5);

		assertThrows(JacksonDeserializationException.class, () -> multiColumnConfig(Map.of("queryTimeout", "0s")));
	}

	// ---------- 값 없음 정책 ----------

	@Test
	public void clearPolicyRejectsElementsExchangedAsJson() throws Exception {
		// Info.Detail은 Collection이므로 JSON으로 주고받으며 비울 수 없다.
		MultiColumnCollectionAssetVariableConfig config = MultiColumnCollectionAssetVariableConfig.deserializeFields(
				JSON.valueToTree(Map.of("element", "Data:Info", "jdbcConfig", JDBC, "table", "t",
										"onNoRecord", "CLEAR",
										"columns", List.of(Map.of("column", "detail", "subPath", "Detail")))));
		MultiColumnCollectionAssetVariable var = new MultiColumnCollectionAssetVariable(config) {
			@Override protected Connection connect() { return conn; }
		};
		AssetVariableException e = assertThrows(AssetVariableException.class, () -> var.initialize(lookup));
		assertTrue(e.getMessage(), e.getMessage().contains("Detail"));
	}

	@Test
	public void multiRowKeepsValueOfMissingKey() throws Exception {
		MultiRowAssetVariable var = multiRow(null);
		// 조회1: A, B / 조회2: A만
		when(rs.next()).thenReturn(true, true, false, true, false);
		when(rs.getString(1)).thenReturn("A", "B", "A");
		when(rs.getObject(2)).thenReturn(1.0, 2.0, 10.0);

		var.read();
		SubmodelElement second = var.read();
		assertEquals("10.0", value(second, "A"));
		assertEquals("2.0", value(second, "B"));
	}

	// ---------- writeColumn, readOnly ----------

	@Test
	public void computedColumnRequiresWriteColumnOrReadOnly() throws Exception {
		List<Map<String,Object>> computed = List.of(Map.of("column", "avg(x)", "subPath", "X"),
													Map.of("column", "Y", "subPath", "Y"));
		assertThrows(JacksonDeserializationException.class,
					() -> multiColumnConfig(Map.of("updateMode", "APPEND", "columns", computed)));
		// 쓰기를 쓰지 않으면 계산 컬럼도 받는다.
		multiColumnConfig(Map.of("columns", computed));

		MultiColumnCollectionAssetVariableConfig withWriteColumn = multiColumnConfig(Map.of("updateMode", "APPEND",
				"columns", List.of(Map.of("column", "avg(x)", "subPath", "X", "writeColumn", "x"),
									Map.of("column", "Y", "subPath", "Y"))));
		assertEquals("insert into t (x, Y) values (?, ?)", withWriteColumn.getUpdateQuery());

		MultiColumnCollectionAssetVariableConfig withReadOnly = multiColumnConfig(Map.of("updateMode", "UPDATE",
				"where", "id = 1",
				"columns", List.of(Map.of("column", "avg(x)", "subPath", "X", "readOnly", true),
									Map.of("column", "Y", "subPath", "Y"))));
		assertEquals("update t set Y = ? where id = 1", withReadOnly.getUpdateQuery());

		assertThrows(JacksonDeserializationException.class, () -> multiColumnConfig(Map.of("columns",
				List.of(Map.of("column", "avg(x)", "subPath", "X", "writeColumn", "x", "readOnly", true)))));
	}

	@Test
	public void readOnlyColumnIsNotBound() throws Exception {
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("updateMode", "UPDATE", "where", "id = 1",
				"columns", List.of(Map.of("column", "avg(X)", "subPath", "X", "readOnly", true),
									Map.of("column", "Y", "subPath", "Y"))));
		when(pstmt.executeUpdate()).thenReturn(1);

		var.update(collection("Pose", property("X", "1"), property("Y", "2")));
		verify(pstmt).setObject(1, 2.0);
		verify(pstmt, never()).setObject(eq(2), any());
	}

	@Test
	public void partialUpdateOfReadOnlyColumnIsRejected() throws Exception {
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("updateMode", "UPDATE", "where", "id = 1",
				"columns", List.of(Map.of("column", "avg(X)", "subPath", "X", "readOnly", true),
									Map.of("column", "Y", "subPath", "Y"))));
		when(rs.next()).thenReturn(true);
		when(rs.getObject(1)).thenReturn(1.0);
		when(rs.getObject(2)).thenReturn(2.0);

		assertThrows(AssetVariableException.class, () -> var.updatePart("X", property("X", "999")));
		verify(pstmt, never()).executeUpdate();
		// 캐시에 999가 남지 않고 DB의 값을 돌려준다.
		assertEquals(1.0, Double.parseDouble(value(var.read(), "X")), 0);
	}

	@Test
	public void cacheIsReloadedAfterSaveWhenReadOnlyColumnsExist() throws Exception {
		// X = Y * 2 인 계산 컬럼
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("updateMode", "UPDATE", "where", "id = 1",
				"validPeriod", "1h",
				"columns", List.of(Map.of("column", "Y * 2", "subPath", "X", "readOnly", true),
									Map.of("column", "Y", "subPath", "Y"))));
		when(pstmt.executeUpdate()).thenReturn(1);
		when(rs.next()).thenReturn(true);
		when(rs.getObject(1)).thenReturn(6.0);
		when(rs.getObject(2)).thenReturn(3.0);

		var.update(collection("Pose", property("X", "2"), property("Y", "3")));
		// 유효 기간 안이어도 저장 후에는 DB를 다시 읽어 계산 결과를 돌려준다.
		SubmodelElement read = var.read();
		verify(stmt).executeQuery(anyString());
		assertEquals(6.0, Double.parseDouble(value(read, "X")), 0);
	}

	@Test
	public void cacheIsKeptAfterSaveWithoutReadOnlyColumns() throws Exception {
		MultiColumnCollectionAssetVariable var = multiColumn(Map.of("updateMode", "UPDATE", "where", "id = 1",
																	"validPeriod", "1h"));
		when(pstmt.executeUpdate()).thenReturn(1);

		var.update(collection("Pose", property("X", "1"), property("Y", "2")));
		var.read();
		verify(stmt, never()).executeQuery(anyString());
	}

	@Test
	public void quotedIdentifiersAreSimpleColumns() throws Exception {
		MultiColumnCollectionAssetVariableConfig config = multiColumnConfig(Map.of("updateMode", "UPDATE",
				"where", "id = 1",
				"columns", List.of(Map.of("column", "\"Sensor Value\"", "subPath", "X"),
									Map.of("column", "t.\"a.b\"", "subPath", "Y"))));
		assertEquals("update t set \"Sensor Value\" = ?, \"a.b\" = ? where id = 1", config.getUpdateQuery());

		assertEquals(List.of("t", "\"a.b\""), ColumnToSubPath.splitQualifiedName("t.\"a.b\""));
		assertEquals(List.of("s", "`my col`", "[x.y]"), ColumnToSubPath.splitQualifiedName("s.`my col`.[x.y]"));
		assertEquals(List.of("\"a\"\"b\""), ColumnToSubPath.splitQualifiedName("\"a\"\"b\""));
		for ( String expr: new String[] { "avg(x)", "x AS y", "t.", ".x", "\"open", "\"\"", "a..b", "a b" } ) {
			assertNull(expr, ColumnToSubPath.splitQualifiedName(expr));
		}
	}

	// ---------- helpers ----------

	private MultiColumnCollectionAssetVariableConfig multiColumnConfig(Map<String,Object> extra) {
		Map<String,Object> fields = new HashMap<>(Map.of("element", "Data:Pose", "jdbcConfig", JDBC, "table", "t",
														"columns", POSE_COLUMNS));
		fields.putAll(extra);
		try {
			return MultiColumnCollectionAssetVariableConfig.deserializeFields(JSON.valueToTree(fields));
		}
		catch ( java.io.IOException e ) {
			throw new AssertionError(e);
		}
	}

	private MultiColumnCollectionAssetVariable multiColumn(Map<String,Object> extra) throws Exception {
		MultiColumnCollectionAssetVariable var = new MultiColumnCollectionAssetVariable(multiColumnConfig(extra)) {
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

	private static String value(SubmodelElement smc, String idShort) {
		return ((SubmodelElementCollection)smc).getValue().stream().filter(e -> idShort.equals(e.getIdShort()))
				.map(e -> ((Property)e).getValue()).findFirst().orElseThrow();
	}

	private static Property property(String idShort, String value) {
		return new DefaultProperty.Builder().idShort(idShort).valueType(DataTypeDefXsd.DOUBLE).value(value).build();
	}

	private static SubmodelElementCollection collection(String idShort, SubmodelElement... members) {
		return new DefaultSubmodelElementCollection.Builder().idShort(idShort)
														.value(new ArrayList<>(List.of(members))).build();
	}
}
