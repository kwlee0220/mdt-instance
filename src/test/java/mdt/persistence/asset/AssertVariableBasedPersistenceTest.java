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
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

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

	private static AssetVariable variable(String elementPath) {
		ElementLocation loc = mock(ElementLocation.class);
		when(loc.getSubmodelId()).thenReturn(SUBMODEL_ID);
		when(loc.getElementPath()).thenReturn(elementPath);

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
