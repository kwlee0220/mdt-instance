package mdt.persistence.asset.jdbc;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.StringWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.AasSubmodelElements;
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
import org.slf4j.Logger;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import utils.json.JacksonDeserializationException;

import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;

/**
 * JDBC AssetVariable의 값 매핑과 설정 검증을 가짜 JDBC 객체로 검증한다.
 */
public class JdbcAssetVariableMappingTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Map<String,Object> JDBC = Map.of("jdbcUrl", "jdbc:postgresql://localhost/test",
															"user", "user", "password", "secret");
	private static final List<Map<String,String>> POSE_COLUMNS
			= List.of(Map.of("column", "X", "subPath", "X"), Map.of("column", "Y", "subPath", "Y"));

	private MDTModelLookup lookup;
	private Connection conn;
	private Statement stmt;
	private ResultSet rs;

	@Before
	public void setUp() throws Exception {
		SubmodelElementCollection template = collection(null, property("LotNo", DataTypeDefXsd.STRING, ""),
				collection("Info", property("Qty", DataTypeDefXsd.INT, "0")),
				property("Note", DataTypeDefXsd.STRING, "keep"));
		Submodel data = new DefaultSubmodel.Builder().idShort("Data").id("urn:test:data")
				.submodelElements(new ArrayList<>(List.of(
						collection("Pose", property("X", DataTypeDefXsd.DOUBLE, "0"), property("Y", DataTypeDefXsd.DOUBLE, "0")),
						collection("Shape", property("W", DataTypeDefXsd.DOUBLE, "0"), property("H", DataTypeDefXsd.DOUBLE, "0")),
						list("Lots"),
						list("TemplatedLots", template))))
				.build();
		lookup = mock(MDTModelLookup.class);
		when(lookup.getSubmodelByIdShort("Data")).thenReturn(data);

		conn = mock(Connection.class);
		stmt = mock(Statement.class);
		rs = mock(ResultSet.class);
		when(conn.createStatement()).thenReturn(stmt);
		when(stmt.executeQuery(anyString())).thenReturn(rs);
	}

	// ---------- #5 multi-column-list ----------

	@Test
	public void listWithoutTemplateUsesSqlTypesAndNoIdShort() throws Exception {
		ResultSetMetaData meta = mock(ResultSetMetaData.class);
		when(rs.getMetaData()).thenReturn(meta);
		when(meta.getColumnCount()).thenReturn(3);
		when(meta.getColumnType(1)).thenReturn(Types.VARCHAR);
		when(meta.getColumnType(2)).thenReturn(Types.INTEGER);
		when(meta.getColumnType(3)).thenReturn(Types.OTHER);		// 대응 타입 없음 → xs:string
		when(rs.next()).thenReturn(true, true, false);
		when(rs.getObject(1)).thenReturn("L1", "L2");
		when(rs.getObject(2)).thenReturn(5, 7);
		when(rs.getObject(3)).thenReturn(json("{\"a\":1}"), (Object)null);

		MultiColumnCollectionListAssetVariable var = list("Data:Lots", List.of(
				Map.of("column", "lot_no", "subPath", "LotNo"), Map.of("column", "qty", "subPath", "Qty"),
				Map.of("column", "extra", "subPath", "Extra")));
		List<SubmodelElement> rows = ((SubmodelElementList)var.read()).getValue();

		assertEquals(2, rows.size());
		SubmodelElementCollection first = (SubmodelElementCollection)rows.get(0);
		assertNull("list elements must not have an idShort", first.getIdShort());
		assertEquals("L1", prop(first, "LotNo").getValue());
		assertEquals(DataTypeDefXsd.INT, prop(first, "Qty").getValueType());
		assertEquals("5", prop(first, "Qty").getValue());
		assertEquals(DataTypeDefXsd.STRING, prop(first, "Extra").getValueType());
		assertEquals("{\"a\":1}", prop(first, "Extra").getValue());
		assertNull(prop((SubmodelElementCollection)rows.get(1), "Extra").getValue());
	}

	@Test
	public void listWithTemplateUsesModelTypesAndNestedPaths() throws Exception {
		when(rs.next()).thenReturn(true, true, false);
		when(rs.getObject(1)).thenReturn("L1", "L2");
		when(rs.getObject(2)).thenReturn(5, 7);

		MultiColumnCollectionListAssetVariable var = list("Data:TemplatedLots", List.of(
				Map.of("column", "lot_no", "subPath", "LotNo"), Map.of("column", "qty", "subPath", "Info.Qty")));
		List<SubmodelElement> rows = ((SubmodelElementList)var.read()).getValue();

		assertEquals(2, rows.size());
		SubmodelElementCollection second = (SubmodelElementCollection)rows.get(1);
		assertEquals("L2", prop(second, "LotNo").getValue());
		Property qty = prop((SubmodelElementCollection)member(second, "Info"), "Qty");
		assertEquals(DataTypeDefXsd.INT, qty.getValueType());
		assertEquals("7", qty.getValue());
		assertEquals("unmapped members keep the template value", "keep", prop(second, "Note").getValue());
		verify(rs, never()).getMetaData();
	}

	@Test
	public void listWithoutTemplateRejectsNestedSubPath() throws Exception {
		assertThrows(AssetVariableException.class, () -> list("Data:Lots",
				List.of(Map.of("column", "qty", "subPath", "Info.Qty"))));
	}

	// ---------- #6 onNoRecord ----------

	@Test
	public void noRecordPolicies() throws Exception {
		when(rs.next()).thenReturn(true, false);
		when(rs.getObject(1)).thenReturn(1.0);
		when(rs.getObject(2)).thenReturn(2.0);
		MultiColumnCollectionAssetVariable clear = pose(Map.of("onNoRecord", "clear"));
		assertEquals("1.0", prop((SubmodelElementCollection)clear.read(), "X").getValue());
		assertNull(prop((SubmodelElementCollection)clear.read(), "X").getValue());

		when(rs.next()).thenReturn(false);
		MultiColumnCollectionAssetVariable fail = pose(Map.of("onNoRecord", "FAIL"));
		AssetVariableException e = assertThrows(AssetVariableException.class, fail::read);
		assertTrue(e.getMessage(), e.getMessage().startsWith("No record"));

		MultiColumnCollectionAssetVariable keep = pose(Map.of());
		assertEquals("0", prop((SubmodelElementCollection)keep.read(), "X").getValue());
	}

	@Test
	public void simpleNoRecordPolicies() throws Exception {
		when(rs.next()).thenReturn(true, false);
		when(rs.getObject(1)).thenReturn(5.0);
		SimpleJdbcAssetVariable clear = simple("Data:Pose.X", Map.of("onNoRecord", "Clear"));
		assertEquals("5.0", ((Property)clear.read()).getValue());
		assertNull(((Property)clear.read()).getValue());

		when(rs.next()).thenReturn(false);
		SimpleJdbcAssetVariable fail = simple("Data:Pose.X", Map.of("onNoRecord", "fail"));
		AssetVariableException e = assertThrows(AssetVariableException.class, fail::read);
		assertTrue(e.getMessage(), e.getMessage().startsWith("No record"));

		// 기본값 KEEP: 이전 값(처음이면 모델의 초기값)을 유지한다.
		SimpleJdbcAssetVariable keep = simple("Data:Pose.X");
		assertEquals("0", ((Property)keep.read()).getValue());
	}

	@Test
	public void keepPolicyWarnsOncePerMissingPeriod() throws Exception {
		Logger logger = mock(Logger.class);
		SimpleJdbcAssetVariable keep = simple("Data:Pose.X");
		keep.setLogger(logger);

		// 행 없음 → 없음 → 있음 → 없음: 행이 없어질 때마다 한 번씩만 경고한다.
		when(rs.next()).thenReturn(false, false, true, false);
		when(rs.getObject(1)).thenReturn(7.0);
		keep.read();
		keep.read();
		verify(logger, times(1)).warn(anyString(), any(), any());
		assertEquals("7.0", ((Property)keep.read()).getValue());
		assertEquals("7.0", ((Property)keep.read()).getValue());
		verify(logger, times(2)).warn(anyString(), any(), any());
	}

	@Test
	public void simpleOnNoRecordConfig() throws Exception {
		SimpleJdbcAssetVariableConfig keep = simpleConfig("Data:Pose.X", Map.of());
		assertEquals(NoRecordPolicy.KEEP, keep.getNoRecordPolicy());
		assertFalse(serialize(keep).has("onNoRecord"));

		SimpleJdbcAssetVariableConfig fail = simpleConfig("Data:Pose.X", Map.of("onNoRecord", "fail"));
		assertEquals(NoRecordPolicy.FAIL, fail.getNoRecordPolicy());
		assertEquals("FAIL", serialize(fail).get("onNoRecord").asText());

		assertThrows(JacksonDeserializationException.class,
					() -> simpleConfig("Data:Pose.X", Map.of("onNoRecord", "ignore")));
	}

	@Test
	public void sqlNullBecomesNullPropertyValue() throws Exception {
		// 숫자 타입 변환이 null을 0으로 바꾸던 문제: SQL NULL은 0이 아니라 값 없음이어야 한다.
		when(rs.next()).thenReturn(true);
		when(rs.getObject(1)).thenReturn((Object)null);
		when(rs.getObject(2)).thenReturn(2.0);
		SubmodelElementCollection pose = (SubmodelElementCollection)pose(Map.of()).read();
		assertNull(prop(pose, "X").getValue());
		assertEquals("2.0", prop(pose, "Y").getValue());
	}

	// ---------- #7 non-Property member ----------

	@Test
	public void nonPropertyMemberReadsJsonText() throws Exception {
		SimpleJdbcAssetVariable var = simple("Data:Shape");
		when(rs.next()).thenReturn(true, true);
		// CLOB이나 PostgreSQL json/jsonb 컬럼도 getString()으로 JSON 텍스트를 돌려준다.
		when(rs.getString(1)).thenReturn("{\"W\":1.5,\"H\":2.5}", "not json");

		SubmodelElementCollection shape = (SubmodelElementCollection)var.read();
		assertEquals(1.5, Double.parseDouble(prop(shape, "W").getValue()), 0);
		assertEquals(2.5, Double.parseDouble(prop(shape, "H").getValue()), 0);

		assertThrows(AssetVariableException.class, var::read);
	}

	// ---------- #10 column expressions ----------

	@Test
	public void columnsAreReadByPosition() throws Exception {
		when(rs.next()).thenReturn(true);
		when(rs.getObject(1)).thenReturn(3.0);
		when(rs.getObject(2)).thenReturn(4.0);
		MultiColumnCollectionAssetVariable var = pose(Map.of("columns", List.of(
				Map.of("column", "max(p.x) as mx", "subPath", "X"), Map.of("column", "p.y", "subPath", "Y"))));

		SubmodelElementCollection pose = (SubmodelElementCollection)var.read();
		assertEquals("3.0", prop(pose, "X").getValue());
		verify(stmt).executeQuery("select max(p.x) as mx, p.y from t where id = 1");
		verify(rs, never()).getObject(anyString());
	}

	@Test
	public void writeQueriesUseUnqualifiedColumnNames() throws Exception {
		List<Map<String,String>> qualified = List.of(Map.of("column", "r.X", "subPath", "X"),
														Map.of("column", "r.Y", "subPath", "Y"));
		assertEquals("insert into t (X, Y) values (?, ?)",
					poseConfig(Map.of("columns", qualified, "updateMode", "append")).getUpdateQuery());
		assertEquals("update t set X = ?, Y = ? where id = 1",
					poseConfig(Map.of("columns", qualified, "updateMode", "Update")).getUpdateQuery());
	}

	// ---------- #9 config validation ----------

	@Test
	public void multiColumnConfigValidation() throws Exception {
		assertBadConfig(Map.of("updateMode", "bogus"));
		assertBadConfig(Map.of("onNoRecord", "ignore"));
		assertBadConfig(Map.of("columns", List.of()));
		assertBadConfig(Map.of("columns", List.of(Map.of("column", "X"))));
		assertBadConfig(Map.of("columns", List.of(Map.of("column", "X", "subPath", "X"), Map.of("column", "Z", "subPath", "X"))));
		assertBadConfig(Map.of("table", ""));

		// UPDATE 모드는 whereClause가 없으면 모든 행을 갱신하므로 거부한다. APPEND는 whereClause 없이 쓸 수 있다.
		Map<String,Object> noWhere = new HashMap<>(poseFields(Map.of("updateMode", "UPDATE")));
		noWhere.remove("whereClause");
		assertThrows(JacksonDeserializationException.class,
					() -> MultiColumnCollectionAssetVariableConfig.deserializeFields(JSON.valueToTree(noWhere)));
		noWhere.put("updateMode", "APPEND");
		MultiColumnCollectionAssetVariableConfig append
								= MultiColumnCollectionAssetVariableConfig.deserializeFields(JSON.valueToTree(noWhere));
		assertEquals("select X, Y from t", append.getReadQuery());
		assertFalse(serialize(append).has("whereClause"));
	}

	@Test
	public void multiRowConfigValidationAndOrder() throws Exception {
		Map<String,Object> fields = new HashMap<>(Map.of("element", "Data:Pose", "jdbcConfig", JDBC,
				"readQuery", "select k, v from t",
				"rows", List.of(Map.of("key", "C", "subPath", "Y"), Map.of("key", "A", "subPath", "X"))));
		MultiRowAssetVariableConfig config = MultiRowAssetVariableConfig.deserializeFields(JSON.valueToTree(fields));
		assertEquals(List.of("C", "A"), new ArrayList<>(config.getKeyToSubpathMapping().keySet()));
		JsonNode rows = serialize(config).get("rows");
		assertEquals("C", rows.get(0).get("key").asText());
		assertEquals("A", rows.get(1).get("key").asText());

		for ( Map<String,Object> bad: List.<Map<String,Object>>of(
				Map.of("rows", List.of(Map.of("key", "A", "subPath", "X"), Map.of("key", "A", "subPath", "Y"))),
				Map.of("rows", List.of()), Map.of("readQuery", " ")) ) {
			Map<String,Object> f = new HashMap<>(fields);
			f.putAll(bad);
			assertThrows(bad.toString(), JacksonDeserializationException.class,
						() -> MultiRowAssetVariableConfig.deserializeFields(JSON.valueToTree(f)));
		}
		Map<String,Object> noRows = new HashMap<>(fields);
		noRows.remove("rows");
		assertThrows(JacksonDeserializationException.class,
					() -> MultiRowAssetVariableConfig.deserializeFields(JSON.valueToTree(noRows)));
	}

	@Test
	public void simpleConfigRequiresReadQuery() {
		assertThrows(JacksonDeserializationException.class,
					() -> SimpleJdbcAssetVariableConfig.deserializeFields(JSON.valueToTree(
							Map.of("element", "Data:Shape", "jdbcConfig", JDBC, "table", "ignored"))));
	}

	// ---------- helpers ----------

	// ---------- where / orderBy / limit ----------

	@Test
	public void rowSelectionFieldsBuildQueries() throws Exception {
		MultiColumnCollectionAssetVariableConfig all = poseConfigWithoutWhereClause(Map.of(
				"where", "id = 1", "orderBy", "ts desc", "limit", 1, "updateMode", "update"));
		assertEquals("select X, Y from t where id = 1 order by ts desc limit 1", all.getReadQuery());
		// UPDATE 질의에는 where만 쓰인다.
		assertEquals("update t set X = ?, Y = ? where id = 1", all.getUpdateQuery());

		MultiColumnCollectionAssetVariableConfig latest = poseConfigWithoutWhereClause(Map.of(
				"orderBy", "id desc", "limit", 1, "updateMode", "append"));
		assertEquals("select X, Y from t order by id desc limit 1", latest.getReadQuery());
		assertEquals("insert into t (X, Y) values (?, ?)", latest.getUpdateQuery());

		// 직렬화할 때는 읽어 들인 방식을 유지한다.
		JsonNode json = serialize(all);
		assertEquals("id = 1", json.get("where").asText());
		assertEquals("ts desc", json.get("orderBy").asText());
		assertEquals(1, json.get("limit").asInt());
		assertFalse(json.has("whereClause"));
		assertEquals("where id = 1", serialize(poseConfig(Map.of())).get("whereClause").asText());
		assertFalse(serialize(poseConfig(Map.of())).has("where"));
	}

	@Test
	public void invalidRowSelectionIsRejected() throws Exception {
		// whereClause와 새 필드는 함께 쓸 수 없다.
		assertBadConfig(Map.of("where", "id = 2"));
		assertBadConfig(Map.of("limit", 1));
		// 키워드를 쓰지 않는다.
		assertBadNewSelection(Map.of("where", "WHERE id = 1"));
		assertBadNewSelection(Map.of("orderBy", "order by id"));
		assertBadNewSelection(Map.of("where", " "));
		for ( Object limit: List.of(0, -1, "1", 1.5) ) {
			assertBadNewSelection(Map.of("limit", limit));
		}
		// UPDATE 모드는 행을 거르는 조건(where)이 있어야 한다.
		assertBadNewSelection(Map.of("orderBy", "id desc", "limit", 1, "updateMode", "UPDATE"));
	}

	@Test
	public void listAcceptsColumnsAlias() throws Exception {
		Map<String,Object> fields = new HashMap<>(Map.of("element", "Data:Lots", "jdbcConfig", JDBC, "table", "lots",
														"where", "qty > 0", "orderBy", "lot_no",
														"columns", List.of(Map.of("column", "lot_no", "subPath", "LotNo"))));
		MultiColumnCollectionListAssetVariableConfig config
								= MultiColumnCollectionListAssetVariableConfig.deserializeFields(JSON.valueToTree(fields));
		assertEquals("select lot_no from lots where qty > 0 order by lot_no", config.getReadQuery());
		JsonNode json = serialize(config);
		assertTrue(json.has("columns"));
		assertFalse(json.has("memberColumns"));

		// 이전 이름도 받으며 그 이름으로 직렬화한다.
		fields.put("memberColumns", fields.remove("columns"));
		MultiColumnCollectionListAssetVariableConfig old
								= MultiColumnCollectionListAssetVariableConfig.deserializeFields(JSON.valueToTree(fields));
		assertTrue(serialize(old).has("memberColumns"));

		// 두 이름을 함께 쓸 수 없다.
		fields.put("columns", fields.get("memberColumns"));
		assertThrows(JacksonDeserializationException.class,
					() -> MultiColumnCollectionListAssetVariableConfig.deserializeFields(JSON.valueToTree(fields)));
	}

	private MultiColumnCollectionAssetVariableConfig poseConfigWithoutWhereClause(Map<String,Object> overrides)
		throws Exception {
		Map<String,Object> fields = new HashMap<>(poseFields(Map.of()));
		fields.remove("whereClause");
		fields.putAll(overrides);
		return MultiColumnCollectionAssetVariableConfig.deserializeFields(JSON.valueToTree(fields));
	}

	private void assertBadNewSelection(Map<String,Object> overrides) {
		assertThrows(overrides.toString(), JacksonDeserializationException.class,
					() -> poseConfigWithoutWhereClause(overrides));
	}

	private void assertBadConfig(Map<String,Object> overrides) {
		assertThrows(overrides.toString(), JacksonDeserializationException.class, () -> poseConfig(overrides));
	}

	private Map<String,Object> poseFields(Map<String,Object> overrides) {
		Map<String,Object> fields = new HashMap<>(Map.of("element", "Data:Pose", "jdbcConfig", JDBC, "table", "t",
														"whereClause", "where id = 1", "columns", POSE_COLUMNS));
		fields.putAll(overrides);
		return fields;
	}

	private MultiColumnCollectionAssetVariableConfig poseConfig(Map<String,Object> overrides) throws Exception {
		return MultiColumnCollectionAssetVariableConfig.deserializeFields(JSON.valueToTree(poseFields(overrides)));
	}

	private MultiColumnCollectionAssetVariable pose(Map<String,Object> overrides) throws Exception {
		MultiColumnCollectionAssetVariable var = new MultiColumnCollectionAssetVariable(poseConfig(overrides)) {
			@Override protected Connection connect() { return conn; }
		};
		var.initialize(lookup);
		return var;
	}

	private MultiColumnCollectionListAssetVariable list(String element, List<Map<String,String>> columns) throws Exception {
		MultiColumnCollectionListAssetVariableConfig config = MultiColumnCollectionListAssetVariableConfig.deserializeFields(
				JSON.valueToTree(Map.of("element", element, "jdbcConfig", JDBC, "table", "lots", "memberColumns", columns)));
		MultiColumnCollectionListAssetVariable var = new MultiColumnCollectionListAssetVariable(config) {
			@Override protected Connection connect() { return conn; }
		};
		var.initialize(lookup);
		return var;
	}

	private SimpleJdbcAssetVariable simple(String element) throws Exception {
		return simple(element, Map.of());
	}

	private SimpleJdbcAssetVariableConfig simpleConfig(String element, Map<String,Object> overrides) throws Exception {
		Map<String,Object> fields = new HashMap<>(Map.of("element", element, "jdbcConfig", JDBC,
														"readQuery", "select v from t"));
		fields.putAll(overrides);
		return SimpleJdbcAssetVariableConfig.deserializeFields(JSON.valueToTree(fields));
	}

	private SimpleJdbcAssetVariable simple(String element, Map<String,Object> overrides) throws Exception {
		SimpleJdbcAssetVariableConfig config = simpleConfig(element, overrides);
		SimpleJdbcAssetVariable var = new SimpleJdbcAssetVariable(config) {
			@Override protected Connection connect() { return conn; }
		};
		var.initialize(lookup);
		return var;
	}

	private static JsonNode serialize(mdt.persistence.asset.AssetVariableConfig config) throws Exception {
		StringWriter out = new StringWriter();
		try ( JsonGenerator gen = JSON.createGenerator(out) ) {
			gen.writeStartObject();
			config.serializeFields(gen);
			gen.writeEndObject();
		}
		return JSON.readTree(out.toString());
	}

	/** 문자열이 아닌 드라이버 객체(예: PGobject)를 흉내 낸다. */
	private static Object json(String text) {
		return new Object() {
			@Override public String toString() { return text; }
		};
	}

	private static SubmodelElement member(SubmodelElementCollection smc, String idShort) {
		return smc.getValue().stream().filter(e -> idShort.equals(e.getIdShort())).findFirst().orElseThrow();
	}

	private static Property prop(SubmodelElementCollection smc, String idShort) {
		return (Property)member(smc, idShort);
	}

	private static Property property(String idShort, DataTypeDefXsd type, String value) {
		return new DefaultProperty.Builder().idShort(idShort).valueType(type).value(value).build();
	}

	private static SubmodelElementCollection collection(String idShort, SubmodelElement... members) {
		return new DefaultSubmodelElementCollection.Builder().idShort(idShort).value(new ArrayList<>(List.of(members))).build();
	}

	private static SubmodelElementList list(String idShort, SubmodelElement... members) {
		return new DefaultSubmodelElementList.Builder().idShort(idShort)
				.typeValueListElement(AasSubmodelElements.SUBMODEL_ELEMENT_COLLECTION)
				.value(new ArrayList<>(List.of(members))).build();
	}
}
