package mdt;

import static org.junit.Assert.*;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultEnvironment;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.fraunhofer.iosb.ilt.faaast.service.Service;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.model.value.PropertyValue;
import de.fraunhofer.iosb.ilt.faaast.service.config.ServiceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.filestorage.memory.FileStorageInMemoryConfig;
import de.fraunhofer.iosb.ilt.faaast.service.messagebus.internal.MessageBusInternalConfig;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.memory.PersistenceInMemoryConfig;

import mdt.persistence.MDTModelLookup;


/**
 * {@link FaaastRuntime}의 값 갱신이 FA³ST의 오류 응답을 {@link ElementUpdateException}으로 알리는지 검증한다.
 * 메모리 persistence를 쓰는 실제 FA³ST {@link Service}를 띄운다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class FaaastRuntimeValueUpdaterTest {
	private static final String SUBMODEL_ID = "urn:mdt:test:state";
	
	private Service m_service;
	private FaaastRuntime m_runtime;
	
	@Before
	public void setUp() throws Exception {
		Environment env = new DefaultEnvironment.Builder()
								.submodels(new DefaultSubmodel.Builder()
												.id(SUBMODEL_ID)
												.idShort("State")
												.submodelElements(new DefaultProperty.Builder()
																		.idShort("Temperature")
																		.valueType(DataTypeDefXsd.DOUBLE)
																		.value("0.0")
																		.build())
												.submodelElements(new DefaultProperty.Builder()
																		.idShort("Status")
																		.valueType(DataTypeDefXsd.STRING)
																		.value("idle")
																		.build())
												.build())
								.build();
		ServiceConfig config = ServiceConfig.builder()
											.core(CoreConfig.builder().build())
											.persistence(PersistenceInMemoryConfig.builder().initialModel(env).build())
											.fileStorage(FileStorageInMemoryConfig.builder().build())
											.messageBus(MessageBusInternalConfig.builder().build())
											.build();
		m_service = new Service(config);
		m_service.start();
		m_runtime = new FaaastRuntime(m_service);
	}
	
	@After
	public void tearDown() {
		if ( m_service != null ) {
			m_service.stop();
		}
	}

	@Test
	public void testUpdateAppliesValue() {
		ElementValueUpdater updater = m_runtime.newValueUpdater(location("Temperature"));
		updater.update("23.5");
		assertEquals(23.5, Double.parseDouble(propertyValue("Temperature")), 0.0);
		
		m_runtime.newValueUpdater(location("Status")).update("\"running\"");
		assertEquals("running", propertyValue("Status"));
	}

	@Test
	public void testQuotedValueIsAcceptedForNumericProperty() {
		// MQTT 구독의 format=text는 payload를 JSON 문자열로 감싸 넘기므로, 숫자 Property도 따옴표 값을 받아야 한다.
		m_runtime.newValueUpdater(location("Temperature")).update("\"23.5\"");
		assertEquals(23.5, Double.parseDouble(propertyValue("Temperature")), 0.0);
	}

	@Test
	public void testValueType() {
		assertEquals(PropertyValue.class, m_runtime.newValueUpdater(location("Temperature")).getValueType());
	}

	@Test
	public void testMalformedValueFails() {
		ElementValueUpdater updater = m_runtime.newValueUpdater(location("Temperature"));
		
		// 예전에는 FA³ST가 오류 응답을 돌려주어도 예외 없이 무시되었다.
		ElementUpdateException e = assertThrows(ElementUpdateException.class, () -> updater.update("abc"));
		assertNotNull(e.getStatusCode());
		assertFalse(e.getStatusCode().isSuccess());
		assertTrue(e.getMessage(), e.getMessage().contains("Temperature"));
		assertEquals(0.0, Double.parseDouble(propertyValue("Temperature")), 0.0);
	}

	@Test
	public void testSetSubmodelElementValueByPathReportsFailure() {
		// 타입을 매번 조회하는 기존 메서드도 오류 응답을 예외로 알린다.
		assertThrows(ElementUpdateException.class,
					() -> m_runtime.setSubmodelElementValueByPath(SUBMODEL_ID, "NoSuchElement", "1.0"));
	}

	@Test
	public void testMissingElementFailsWhenCreatingUpdater() {
		ElementUpdateException e = assertThrows(ElementUpdateException.class,
												() -> m_runtime.newValueUpdater(location("NoSuchElement")));
		assertTrue(e.getMessage(), e.getMessage().contains("State:NoSuchElement"));
	}
	
	private String propertyValue(String path) {
		return ((Property)m_runtime.getSubmodelElementByPath(SUBMODEL_ID, path)).getValue();
	}
	
	private static ElementLocation location(String path) {
		return new ElementLocation() {
			@Override public void activate(MDTModelLookup lookup) { }
			@Override public String getSubmodelId() { return SUBMODEL_ID; }
			@Override public String getSubmodelIdShort() { return "State"; }
			@Override public String getElementPath() { return path; }
			@Override public String toStringExpr() { return "State:" + path; }
		};
	}
}
