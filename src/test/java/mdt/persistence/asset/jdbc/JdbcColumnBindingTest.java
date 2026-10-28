package mdt.persistence.asset.jdbc;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import mdt.aas.DataTypes;
import mdt.model.sm.SubmodelUtils;
import mdt.persistence.asset.AssetVariableException;
import mdt.persistence.asset.ElementValueSetter;


/**
 * {@link JdbcColumnBinding}과 {@link ElementValueSetter}의 값 변환을 검사한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JdbcColumnBindingTest {
	private ResultSet rs;
	private PreparedStatement ps;
	private SubmodelElementCollection root;

	@Before
	public void setUp() throws Exception {
		rs = mock(ResultSet.class);
		ps = mock(PreparedStatement.class);

		ResultSetMetaData meta = mock(ResultSetMetaData.class);
		when(rs.getMetaData()).thenReturn(meta);
		when(meta.getColumnLabel(1)).thenReturn("temp_col");

		root = newRoot();
	}

	@Test
	public void bindingKindFollowsTargetElement() {
		assertTrue(JdbcColumnBinding.of(root, "Temp") instanceof PropertyColumnBinding);
		assertTrue(JdbcColumnBinding.of(root, "Shape") instanceof JsonColumnBinding);
		// DataTypes가 지원하지 않는 valueType은 기존과 같이 JSON으로 주고받는다.
		assertTrue(JdbcColumnBinding.of(root, "Count") instanceof JsonColumnBinding);
		assertEquals("Shape.W", JdbcColumnBinding.of(root, "Shape.W").getSubPath());
	}

	@Test
	public void missingSubPathFailsAtCreation() {
		assertThrows(AssetVariableException.class, () -> JdbcColumnBinding.of(root, "NoSuch"));
	}

	@Test
	public void emptySubPathTargetsRoot() throws Exception {
		Property temp = SubmodelUtils.newProperty("Temp", "1.0", DataTypeDefXsd.DOUBLE);
		JdbcColumnBinding binding = JdbcColumnBinding.of(temp, "");
		when(rs.getObject(1)).thenReturn(2.5);

		binding.read(rs, 1, temp);
		assertEquals("2.5", temp.getValue());
	}

	@Test
	public void propertyReadConvertsJdbcObjectAndNull() throws Exception {
		JdbcColumnBinding temp = JdbcColumnBinding.of(root, "Temp");
		JdbcColumnBinding time = JdbcColumnBinding.of(root, "Time");
		Instant ts = Instant.parse("2026-10-28T01:02:03Z");
		when(rs.getObject(1)).thenReturn(12.5, (Object)null);
		when(rs.getObject(2)).thenReturn(java.sql.Timestamp.from(ts));

		temp.read(rs, 1, root);
		time.read(rs, 2, root);
		assertEquals(12.5, Double.parseDouble(prop(root, "Temp").getValue()), 0);
		assertEquals(ts, DataTypes.DATE_TIME.parseValueString(prop(root, "Time").getValue()));

		// SQL NULL은 0이 아니라 값 없음이 된다.
		temp.read(rs, 1, root);
		assertNull(prop(root, "Temp").getValue());
	}

	@Test
	public void propertyReadFailureNamesSubPathAndColumn() throws Exception {
		when(rs.getObject(1)).thenReturn("not a number");

		AssetVariableException error = assertThrows(AssetVariableException.class,
													() -> JdbcColumnBinding.of(root, "Temp").read(rs, 1, root));
		assertTrue(error.getMessage(), error.getMessage().contains("subPath=Temp"));
		assertTrue(error.getMessage(), error.getMessage().contains("temp_col"));
	}

	@Test
	public void propertyBindConvertsValueAndNull() throws Exception {
		JdbcColumnBinding temp = JdbcColumnBinding.of(root, "Temp");

		temp.bind(ps, 3, root);
		verify(ps).setObject(3, 1.0);

		prop(root, "Temp").setValue(null);
		temp.bind(ps, 3, root);
		verify(ps).setObject(3, null);
	}

	@Test
	public void propertyClearSetsNull() {
		JdbcColumnBinding.of(root, "Temp").clear(root);
		assertNull(prop(root, "Temp").getValue());
	}

	@Test
	public void jsonReadUsesStringAndKeepsElementOnNull() throws Exception {
		JdbcColumnBinding shape = JdbcColumnBinding.of(root, "Shape");
		when(rs.getString(1)).thenReturn("{\"W\":1.5,\"H\":2.5}", (String)null);

		shape.read(rs, 1, root);
		SubmodelElementCollection smc = (SubmodelElementCollection)member(root, "Shape");
		assertEquals(1.5, Double.parseDouble(prop(smc, "W").getValue()), 0);

		shape.read(rs, 1, root);
		assertEquals(1.5, Double.parseDouble(prop(smc, "W").getValue()), 0);
		verify(rs, never()).getObject(1);
	}

	@Test
	public void jsonBindRoundTrips() throws Exception {
		JdbcColumnBinding shape = JdbcColumnBinding.of(root, "Shape");

		shape.bind(ps, 1, root);
		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(ps).setString(eq(1), json.capture());

		SubmodelElementCollection other = newRoot();
		SubmodelElementCollection otherShape = (SubmodelElementCollection)member(other, "Shape");
		prop(otherShape, "W").setValue("0");
		when(rs.getString(1)).thenReturn(json.getValue());
		shape.read(rs, 1, other);
		assertEquals(3.0, Double.parseDouble(prop(otherShape, "W").getValue()), 0);
	}

	@Test
	public void jsonClearKeepsElement() {
		JdbcColumnBinding.of(root, "Shape").clear(root);
		SubmodelElementCollection smc = (SubmodelElementCollection)member(root, "Shape");
		assertEquals("3.0", prop(smc, "W").getValue());
	}

	@Test
	public void elementValueSetterConvertsJavaValues() {
		Property temp = prop(root, "Temp");
		ElementValueSetter setter = new ElementValueSetter(temp);
		setter.update(temp, 7);
		assertEquals(7.0, Double.parseDouble(temp.getValue()), 0);
		setter.update(temp, null);
		assertNull(temp.getValue());

		SubmodelElementCollection shape = (SubmodelElementCollection)member(root, "Shape");
		ElementValueSetter jsonSetter = new ElementValueSetter(shape);
		jsonSetter.update(shape, "{\"W\":9.0,\"H\":2.0}");
		assertEquals(9.0, Double.parseDouble(prop(shape, "W").getValue()), 0);
		jsonSetter.update(shape, null);
		assertEquals(9.0, Double.parseDouble(prop(shape, "W").getValue()), 0);
		assertThrows(AssetVariableException.class, () -> jsonSetter.update(shape, "not json"));
	}

	private static SubmodelElementCollection newRoot() {
		SubmodelElementCollection shape = SubmodelUtils.newSubmodelElementCollection("Shape", List.of(
				SubmodelUtils.newProperty("W", "3.0", DataTypeDefXsd.DOUBLE),
				SubmodelUtils.newProperty("H", "4.0", DataTypeDefXsd.DOUBLE)));
		return SubmodelUtils.newSubmodelElementCollection("Root", List.of(
				SubmodelUtils.newProperty("Temp", "1.0", DataTypeDefXsd.DOUBLE),
				SubmodelUtils.newProperty("Time", null, DataTypeDefXsd.DATE_TIME),
				SubmodelUtils.newProperty("Count", "5", DataTypeDefXsd.INTEGER),
				shape));
	}

	private static SubmodelElement member(SubmodelElementCollection smc, String idShort) {
		return smc.getValue().stream().filter(e -> idShort.equals(e.getIdShort())).findFirst().orElseThrow();
	}

	private static Property prop(SubmodelElementCollection smc, String idShort) {
		return (Property)member(smc, idShort);
	}
}
