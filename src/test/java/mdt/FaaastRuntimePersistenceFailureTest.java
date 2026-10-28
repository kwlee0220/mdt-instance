package mdt;

import static org.junit.Assert.*;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultEnvironment;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.fraunhofer.iosb.ilt.faaast.service.Service;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.config.ServiceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.filestorage.memory.FileStorageInMemoryConfig;
import de.fraunhofer.iosb.ilt.faaast.service.messagebus.internal.MessageBusInternalConfig;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.memory.PersistenceInMemory;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.memory.PersistenceInMemoryConfig;

import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;


/**
 * 저장 계층(persistence)의 쓰기 실패가 {@link ElementValueUpdater#update}의 {@link ElementUpdateException}으로
 * 드러나는지 검증한다. 쓰기 때 지정한 예외를 던지는 테스트용 persistence로 실제 FA³ST {@link Service}를 띄운다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class FaaastRuntimePersistenceFailureTest {
	private static final String SUBMODEL_ID = "urn:mdt:test:state";

	private Service m_service;
	private ElementValueUpdater m_updater;

	/**
	 * {@link #s_failure}가 설정되어 있으면 {@code update()}에서 그 예외를 던지는 persistence.
	 * FA³ST가 설정 클래스로부터 리플렉션으로 생성하므로 실패 지정은 static 필드로 한다.
	 */
	public static class FailingPersistence extends PersistenceInMemory {
		static volatile Exception s_failure = null;

		@Override
		public void update(SubmodelElementIdentifier identifier, SubmodelElement element)
			throws ResourceNotFoundException {
			Exception failure = s_failure;
			if ( failure != null ) {
				// PersistenceInMemory.update()는 PersistenceException을 선언하지 않지만, Persistence 인터페이스와
				// 실제 persistence(예: 자산 변수 persistence)는 이를 던질 수 있으므로 검사 예외 확인을 우회해 던진다.
				FailingPersistence.<RuntimeException>sneakyThrow(failure);
			}
			super.update(identifier, element);
		}
		
		@SuppressWarnings("unchecked")
		private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
			throw (E)e;
		}
	}

	public static class FailingPersistenceConfig extends PersistenceInMemoryConfig {
		@SuppressWarnings({ "unchecked", "rawtypes" })
		@Override
		protected Class<PersistenceInMemory> getImplementationType() {
			return (Class)FailingPersistence.class;
		}
	}

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
												.build())
								.build();
		FailingPersistenceConfig persistence = new FailingPersistenceConfig();
		persistence.setInitialModel(env);
		ServiceConfig config = ServiceConfig.builder()
											.core(CoreConfig.builder().build())
											.persistence(persistence)
											.fileStorage(FileStorageInMemoryConfig.builder().build())
											.messageBus(MessageBusInternalConfig.builder().build())
											.build();
		FailingPersistence.s_failure = null;
		m_service = new Service(config);
		m_service.start();
		m_updater = new FaaastRuntime(m_service).newValueUpdater(location("Temperature"));
	}

	@After
	public void tearDown() {
		FailingPersistence.s_failure = null;
		if ( m_service != null ) {
			m_service.stop();
		}
	}

	@Test
	public void testPersistenceExceptionIsReported() {
		FailingPersistence.s_failure = new PersistenceException("disk full");

		ElementUpdateException e = assertThrows(ElementUpdateException.class, () -> m_updater.update("23.5"));
		assertFalse(e.getStatusCode().isSuccess());
		assertTrue(e.getMessage(), e.getMessage().contains("disk full"));
	}

	@Test
	public void testAssetVariableFailureIsReported() {
		// mdt 자산 변수의 쓰기 실패(AssetVariableException, RuntimeException)도 같은 방식으로 드러난다.
		FailingPersistence.s_failure = new AssetVariableException("failed to write to the JDBC table");

		ElementUpdateException e = assertThrows(ElementUpdateException.class, () -> m_updater.update("23.5"));
		assertTrue(e.getMessage(), e.getMessage().contains("failed to write to the JDBC table"));
	}

	@Test
	public void testIllegalArgumentExceptionIsIgnoredByFaaast() {
		// 알려진 한계: FA³ST의 PatchSubmodelElementValueByPathRequestHandler는 persistence.update()의
		// IllegalArgumentException을 일부러 무시하고 성공으로 응답한다. 이 경우 실패를 알 수 없다.
		FailingPersistence.s_failure = new IllegalArgumentException("invalid value for the asset");

		m_updater.update("23.5");		// 예외 없음
		Property prop = (Property)new FaaastRuntime(m_service).getSubmodelElementByPath(SUBMODEL_ID, "Temperature");
		assertEquals("the value is not stored although the update reported success", 0.0,
					Double.parseDouble(prop.getValue()), 0.0);
	}

	@Test
	public void testUpdateSucceedsWithoutFailure() {
		m_updater.update("23.5");
		Property prop = (Property)new FaaastRuntime(m_service).getSubmodelElementByPath(SUBMODEL_ID, "Temperature");
		assertEquals(23.5, Double.parseDouble(prop.getValue()), 0.0);
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
