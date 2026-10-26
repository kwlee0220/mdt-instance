package mdt.persistence.asset;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.Level;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.OutputModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.Page;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.PagingInfo;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelSearchCriteria;

import utils.KeyValue;
import utils.Throwables;
import utils.stream.FStream;

import mdt.ElementLocation;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.PersistenceStack;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AssertVariableBasedPersistence extends PersistenceStack<AssertVariableBasedPersistenceConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(AssertVariableBasedPersistence.class);

	private AssertVariableBasedPersistenceConfig m_config;
	private MDTModelLookup m_lookup;
	private Map<String,List<AssetVariable>> m_assetVariables;	// submodel id -> AssetVariable

	public AssertVariableBasedPersistence() {
		setLogger(s_logger);
	}

	@Override
	public void init(CoreConfig coreConfig, AssertVariableBasedPersistenceConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		super.init(coreConfig, config, serviceContext);
		
		m_config = config;
		getLogger().info("initialized {}, config={}", this, config);
	}

	@Override
	public AssertVariableBasedPersistenceConfig asConfig() {
		return m_config;
	}

	@Override
	public void start() throws PersistenceException {
		super.start();
		
		// 등록된 모든 AssetVariable들을 생성하고 활성화시킨다.
		// 요청에는 submodel id가 오므로 submodel id를 키로 삼는다. 이렇게 하면 요청을 처리할 때
		// 시작 시점의 모델 스냅숏(MDTModelLookup)으로 submodel을 찾을 필요가 없다.
		m_lookup = MDTModelLookup.getInstance();
		m_assetVariables = FStream.from(m_config.getAssetVariableConfigs())
						 			.map(c -> createAssetVariable(c, m_lookup))
						 			.mapToKeyValue(var -> KeyValue.of(var.getElementLocation().getSubmodelId(), var))
						 			.toListMap();
	}
	
	private boolean isNormalModifier(QueryModifier modifier) {
        return modifier.getLevel() == Level.DEFAULT;
	}
	
	@Override
	public SubmodelElement getSubmodelElement(SubmodelElementIdentifier identifier, QueryModifier modifier)
		throws ResourceNotFoundException, PersistenceException {
		if ( modifier instanceof OutputModifier om ) {
			switch ( om.getContent() ) {
				case METADATA:
				case PATH:
				case REFERENCE:
					return getBasePersistence().getSubmodelElement(identifier, modifier);
				default:
					break;
			}
		}
		
		// 주어진 identifier에 해당하는 AssetVariable을 찾는다.
		// 검색되면 해당 AssetVariable을 통해 외부 asset에서 SubmodelElement를 읽어오고,
		// 검색되지 않으면 m_basePersistence를 통해 SubmodelElement를 읽어온다.
		// (실행 중에 추가되었거나 존재하지 않는 submodel은 AssetVariable이 없으므로 base persistence가
		//  처리하며, 없는 경우 FA³ST의 ResourceNotFoundException(404)이 발생한다)
		String elementPath = identifier.getIdShortPath().toString();

		SubmodelElement baseSme = null;
		for ( AssetVariable var : m_assetVariables.getOrDefault(identifier.getSubmodelId(), List.of()) ) {
			if ( !var.isReadable() ) {
				continue;
			}
			
			ElementLocation varLoc = var.getElementLocation();
			
			String varElmPath = varLoc.getElementPath();
			if ( elementPath.equals(varElmPath) ) {
				getLogger().debug("read AssetVariable: {}", var);
				// read()는 매번 새로 만든 복사본을 반환하므로 따로 복사하지 않는다.
				return var.read();
			}
			else if ( AssetVariable.isPathPrefix(varElmPath, elementPath) ) {
				// 요청한 elementPath가 AssetVariable의 elementPath의 일부인 경우
				// AssetVariable의 elementPath에서 요청한 부분만 추출한다.
				String relPath = SubmodelUtils.toRelativeIdShortPath(varElmPath, elementPath);
				SubmodelElement subPart = SubmodelUtils.traverse(var.read(), relPath);
				getLogger().debug("read AssetVariable: {}, sub-path={}", var, relPath);
				return subPart;
			}
			else if ( AssetVariable.isPathPrefix(elementPath, varElmPath) ) {	// element > var
				// AssetVariable을 통해 읽은 SubmodelElement가 요청한 elementPath의 일부인 경우
				// BasePersistence에서 대상 SubmodelElement을 읽어와서 AssetVariable 영역에
				// 해당하는 부분만을 갱신한다.
				if ( baseSme == null ) {
					baseSme = getBasePersistence().getSubmodelElement(identifier, QueryModifier.DEFAULT);
				}
				String relPath = SubmodelUtils.toRelativeIdShortPath(elementPath, varElmPath);
				SubmodelElement subPart = SubmodelUtils.traverse(baseSme, relPath);
				
				SubmodelElement newSme = var.read();
				SubmodelUtils.updateValue(subPart, newSme);
//				ElementValues.update(subPart, ElementValues.getValue(var.read()));
				
				getLogger().debug("read SAssetVariable {} and replace {}", var, elementPath);
			}
		}
		
		// baseSme가 null인 경우는 요청한 영역과 등록된 모든 AssetVariable들의 영역이 겹치지 않은 경우
		// 이므로, BasePersistence를 통해 SubmodelElement를 읽어와 반환한다.
		if ( baseSme == null ) {
			baseSme = getBasePersistence().getSubmodelElement(identifier, QueryModifier.DEFAULT);
		}
		return baseSme;
	}

	@Override
	public void update(SubmodelElementIdentifier identifier, SubmodelElement update)
		throws ResourceNotFoundException, PersistenceException {
		// 주어진 identifier에 해당하는 AssetVariable을 찾는다.
		// 검색되면 해당 AssetVariable을 통해 외부 asset을 갱신하고,
		// 검색되지 않으면 m_basePersistence를 통해 SubmodelElement를 갱신한다.
		String elementPath = identifier.getIdShortPath().toString();

		for ( AssetVariable var : m_assetVariables.getOrDefault(identifier.getSubmodelId(), List.of()) ) {
			if ( !var.isUpdatable() ) {
				continue;
			}
			
			ElementLocation varLoc = var.getElementLocation();
			
			String varElmPath = varLoc.getElementPath();
			if ( elementPath.equals(varElmPath) ) {
				var.update(update);
				getLogger().debug("update AssetVariable: {}", var);
			}
			else if ( AssetVariable.isPathPrefix(varElmPath, elementPath) ) {	// element < var
				// 갱신 영역이 AssetVariable의 영역의 일부인 경우
				// AssetVariable의 SubmodelElement 중 갱신된 부분만 바꿔 다시 저장한다.
				// 동시에 들어온 다른 갱신이 사라지지 않도록, 읽기-수정-저장은 AssetVariable이
				// 자신의 임계 구역에서 수행하게 한다(updatePart).
				String relPath = SubmodelUtils.toRelativeIdShortPath(varElmPath, elementPath);
				var.updatePart(relPath, update);

				getLogger().debug("update AssetVariable: {}, sub-path={}", var, relPath);
			}
			else if ( AssetVariable.isPathPrefix(elementPath, varElmPath) ) {	// element > var
				// 갱신 영역이 AssetVariable의 영역을 포함하는 경우
				// 갱신 영역을 base persistence를 통해 갱신하고,
				// 또한 갱신 영역 중 AssetVariable에 해당하는 영역을 통해 AssetVariable을 갱신한다. 
				String relPath = SubmodelUtils.toRelativeIdShortPath(elementPath, varElmPath);
				SubmodelElement subElm = SubmodelUtils.traverse(update, relPath);
				var.update(subElm);
				
				getLogger().debug("update AssetVariable: {}", var);
			}
		}
		
		getBasePersistence().update(identifier, update);
	}

	@Override
	public Submodel getSubmodel(String id, QueryModifier modifier)
													throws ResourceNotFoundException, PersistenceException {
		// Base Submodel을 읽어온 후, 등록된 AssetVariable들을 통해
		// 해당 SubmodelElement들을 갱신한 후 반환한다.
		Submodel submodel = getBasePersistence().getSubmodel(id, modifier);
		for ( AssetVariable var : m_assetVariables.getOrDefault(submodel.getId(), List.of()) ) {
			if ( var.isReadable() ) {
				String elementPath = var.getElementLocation().getElementPath();
				SubmodelElement buffer = SubmodelUtils.traverse(submodel, elementPath);
				
				ElementValue varValue = ElementValues.getValue(var.read());
				ElementValues.update(buffer, varValue);
				if ( getLogger().isDebugEnabled() ) {
					getLogger().debug("update Submodel {} from AssetVariable {}: value={}",
										submodel.getIdShort(), var.getElementLocation(), varValue);
				}
			}
		}
		return submodel;
	}

	@Override
	public Page<Submodel> findSubmodels(SubmodelSearchCriteria criteria, QueryModifier modifier,
										PagingInfo paging) throws PersistenceException {
		// Level.DEFAULT가 아닌 경우(예: CORE)에는 하위 요소가 생략된 Submodel이 반환되어
		// AssetVariable의 경로를 따라갈 수 없으므로, base persistence의 결과를 그대로 반환한다.
		if ( !isNormalModifier(modifier) ) {
			return getBasePersistence().findSubmodels(criteria, modifier, paging);
		}

		// 검색된 각 Submodel을 등록된 AssetVariable들의 값이 반영된 Submodel로 교체한다.
		Page<Submodel> page = getBasePersistence().findSubmodels(criteria, modifier, paging);
		List<Submodel> submodels = FStream.from(page.getContent())
											.map(sm -> getSubmodelOrStored(sm, modifier))
									        .toList();
		page.setContent(submodels);
		return page;
	}

	/**
	 * 주어진 Submodel에 AssetVariable들의 값을 반영한 Submodel을 반환한다.
	 * <p>
	 * AssetVariable을 읽는 중 오류가 발생하면, Submodel 목록에서 해당 Submodel이 누락되거나
	 * 목록 전체가 실패하지 않도록 base persistence에 저장된 Submodel을 그대로 반환하고 경고를 남긴다.
	 *
	 * @param stored	base persistence에서 검색된 Submodel.
	 * @param modifier	질의 modifier.
	 * @return	AssetVariable 값이 반영된 Submodel. 반영에 실패한 경우는 {@code stored}.
	 */
	private Submodel getSubmodelOrStored(Submodel stored, QueryModifier modifier) {
		try {
			return getSubmodel(stored.getId(), modifier);
		}
		catch ( Exception e ) {
			getLogger().warn("failed to read AssetVariables of Submodel {}, use the stored one: cause={}",
							stored.getIdShort(), "" + Throwables.unwrapThrowable(e));
			return stored;
		}
	}

	@Override
	public void save(Submodel submodel) throws PersistenceException {
		getBasePersistence().save(submodel);

		// 갱신된 Submodel 정보를 해당 AssetVariable들을 통해 외부 asset에 갱신한다.
		for ( AssetVariable var : m_assetVariables.getOrDefault(submodel.getId(), List.of()) ) {
			if ( var.isUpdatable() ) {
				String elementPath = var.getElementLocation().getElementPath();
				SubmodelElement newSme = SubmodelUtils.traverse(submodel, elementPath);
				var.update(newSme);
				if ( getLogger().isDebugEnabled() ) {
					getLogger().debug("update AssetVariable {} from saved Submodel {}: value={}",
							var.getElementLocation(), submodel.getIdShort(), ElementValues.getValue(newSme));
				}
			}
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private AssetVariable createAssetVariable(AssetVariableConfig config, MDTModelLookup lookup) {
		try {
			String configClassName = config.getClass().getName();
			int length = configClassName.length();
			String assetVarClassName = configClassName.substring(0, length-6);
			
			Class assetVarClass = Class.forName(assetVarClassName);
			Constructor ctor = assetVarClass.getDeclaredConstructor(config.getClass());
			AssetVariable assetVar = (AssetVariable)ctor.newInstance(config);
			assetVar.initialize(lookup);
			
			return assetVar;
		}
		catch ( Throwable e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new AssetVariableException("Failed to create an AssetVariable", cause);
		}
	}

	@Override
	public void deleteAll() throws PersistenceException {
		getBasePersistence().deleteAll();
	}
}
