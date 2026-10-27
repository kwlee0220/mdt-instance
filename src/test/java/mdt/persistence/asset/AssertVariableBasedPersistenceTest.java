package mdt.persistence.asset;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementCollection;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.Persistence;

import mdt.ElementLocation;


/**
 * {@link AssertVariableBasedPersistence#save(Submodel)}의 외부 asset 갱신과 base persistence 저장 순서를 검증한다.
 * <p>
 * FA³ST 서비스 없이 검증하기 위해 base persistence는 mock으로 지정하고, 등록된 AssetVariable 목록은
 * 리플렉션으로 설정한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AssertVariableBasedPersistenceTest {
	private static final String SUBMODEL_ID = "urn:test:sm:Telemetry";

	private AssertVariableBasedPersistence m_persistence;
	private Persistence<?> m_base;

	@Before
	public void setUp() {
		m_persistence = new AssertVariableBasedPersistence();
		m_base = mock(Persistence.class);
		m_persistence.setBasePersistence(m_base);
	}

	@Test
	public void testSaveUpdatesAssetBeforeBase() throws Exception {
		AssetVariable var = variable("Temperature");
		setAssetVariables(var);

		Submodel submodel = submodel("Temperature");
		m_persistence.save(submodel);

		InOrder order = inOrder(var, m_base);
		order.verify(var).update(any(SubmodelElement.class));
		order.verify(m_base).save(submodel);
	}

	@Test
	public void testSaveDoesNotChangeBaseWhenAssetUpdateFails() throws Exception {
		AssetVariable var = variable("Temperature");
		doThrow(new AssetVariableException("broker is not connected")).when(var).update(any());
		setAssetVariables(var);

		assertThrows(AssetVariableException.class, () -> m_persistence.save(submodel("Temperature")));
		verify(m_base, never()).save(any(Submodel.class));
	}

	@Test
	public void testSaveWithMissingElementUpdatesNothing() throws Exception {
		AssetVariable present = variable("Temperature");
		AssetVariable missing = variable("Pressure");
		setAssetVariables(present, missing);

		// 저장할 Submodel에 'Pressure'가 없으면 외부 asset을 일부만 갱신하지 않고 전체를 실패시킨다.
		assertThrows(RuntimeException.class, () -> m_persistence.save(submodel("Temperature")));
		verify(present, never()).update(any());
		verify(missing, never()).update(any());
		verify(m_base, never()).save(any(Submodel.class));
	}

	@Test
	public void testSaveSkipsNonUpdatableVariable() throws Exception {
		AssetVariable readOnly = variable("Temperature");
		when(readOnly.isUpdatable()).thenReturn(false);
		setAssetVariables(readOnly);

		Submodel submodel = submodel("Temperature");
		m_persistence.save(submodel);

		verify(readOnly, never()).update(any());
		verify(m_base).save(submodel);
	}

	// 검토 #5: 쓰기 전용 AssetVariable이 연결된 Collection의 하위 요소만 갱신하는 경우,
	// base persistence의 현재 값에 갱신 부분을 반영한 전체 값을 외부에 기록한다.
	@Test
	public void testPartialUpdateOfWriteOnlyVariable() throws Exception {
		AssetVariable var = variable("Measurements");
		setAssetVariables(var);
		SubmodelElementCollection stored = measurements("20", "100");
		when(m_base.getSubmodelElement(any(SubmodelElementIdentifier.class), any(QueryModifier.class)))
			.thenReturn(stored);
		
		SubmodelElementIdentifier id = identifier("Measurements.Temperature");
		Property part = property("Temperature", "25");
		m_persistence.update(id, part);
		
		ArgumentCaptor<SubmodelElement> written = ArgumentCaptor.forClass(SubmodelElement.class);
		InOrder order = inOrder(var, m_base);
		order.verify(var).update(written.capture());
		order.verify(m_base).update(id, part);
		verify(var, never()).updatePart(any(), any());
		
		// 갱신한 Temperature와 base에 저장된 Pressure가 함께 기록된다.
		SubmodelElementCollection whole = (SubmodelElementCollection)written.getValue();
		assertEquals("25", value(whole, "Temperature"));
		assertEquals("100", value(whole, "Pressure"));
		// base persistence가 반환한 객체는 변경하지 않는다.
		assertEquals("20", value(stored, "Temperature"));
	}

	@Test
	public void testPartialUpdateOfWriteOnlyVariableFails() throws Exception {
		AssetVariable var = variable("Measurements");
		doThrow(new AssetVariableException("broker is not connected")).when(var).update(any());
		setAssetVariables(var);
		when(m_base.getSubmodelElement(any(SubmodelElementIdentifier.class), any(QueryModifier.class)))
			.thenReturn(measurements("20", "100"));
		
		assertThrows(AssetVariableException.class,
					() -> m_persistence.update(identifier("Measurements.Temperature"), property("Temperature", "25")));
		verify(m_base, never()).update(any(SubmodelElementIdentifier.class), any(SubmodelElement.class));
	}

	@Test
	public void testPartialUpdateOfReadableVariableUsesUpdatePart() throws Exception {
		AssetVariable var = variable("Measurements");
		when(var.isReadable()).thenReturn(true);
		setAssetVariables(var);
		
		Property part = property("Temperature", "25");
		m_persistence.update(identifier("Measurements.Temperature"), part);
		
		verify(var).updatePart("Temperature", part);
		verify(var, never()).update(any());
		verify(m_base, never()).getSubmodelElement(any(SubmodelElementIdentifier.class), any(QueryModifier.class));
	}
	
	private static SubmodelElementIdentifier identifier(String path) {
		return SubmodelElementIdentifier.builder()
										.submodelId(SUBMODEL_ID)
										.idShortPath(IdShortPath.parse(path))
										.build();
	}
	
	private static Property property(String idShort, String value) {
		return new DefaultProperty.Builder()
								.idShort(idShort)
								.valueType(DataTypeDefXsd.INT)
								.value(value)
								.build();
	}
	
	private static SubmodelElementCollection measurements(String temperature, String pressure) {
		return new DefaultSubmodelElementCollection.Builder()
								.idShort("Measurements")
								.value(List.of(property("Temperature", temperature), property("Pressure", pressure)))
								.build();
	}
	
	private static String value(SubmodelElementCollection coll, String idShort) {
		return coll.getValue().stream()
					.filter(e -> e.getIdShort().equals(idShort))
					.map(e -> ((Property)e).getValue())
					.findFirst().orElseThrow();
	}
	
	private static AssetVariable variable(String elementPath) {
		ElementLocation loc = mock(ElementLocation.class);
		when(loc.getSubmodelId()).thenReturn(SUBMODEL_ID);
		when(loc.getElementPath()).thenReturn(elementPath);
		when(loc.toIdentifier()).thenCallRealMethod();

		AssetVariable var = mock(AssetVariable.class);
		when(var.getElementLocation()).thenReturn(loc);
		when(var.isUpdatable()).thenReturn(true);
		return var;
	}

	private void setAssetVariables(AssetVariable... vars) throws Exception {
		Field field = AssertVariableBasedPersistence.class.getDeclaredField("m_assetVariables");
		field.setAccessible(true);
		field.set(m_persistence, Map.of(SUBMODEL_ID, List.of(vars)));
	}

	private static Submodel submodel(String... propIdShorts) {
		List<SubmodelElement> elements = List.of(propIdShorts).stream()
												.map(idShort -> (SubmodelElement)new DefaultProperty.Builder()
																		.idShort(idShort)
																		.valueType(DataTypeDefXsd.INT)
																		.value("1")
																		.build())
												.toList();
		return new DefaultSubmodel.Builder()
								.id(SUBMODEL_ID)
								.idShort("Telemetry")
								.submodelElements(elements)
								.build();
	}
}
