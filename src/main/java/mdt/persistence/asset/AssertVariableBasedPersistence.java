package mdt.persistence.asset;

import java.lang.reflect.Constructor;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Throwables;
import utils.stream.FStream;

import mdt.ElementLocation;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.value.ElementValues;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.PersistenceStack;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;
import de.fraunhofer.iosb.ilt.faaast.service.model.SubmodelElementIdentifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.Level;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.modifier.QueryModifier;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.Page;
import de.fraunhofer.iosb.ilt.faaast.service.model.api.paging.PagingInfo;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.SubmodelSearchCriteria;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AssertVariableBasedPersistence extends PersistenceStack<AssertVariableBasedPersistenceConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(AssertVariableBasedPersistence.class);

	private AssertVariableBasedPersistenceConfig m_config;
	private MDTModelLookup m_lookup;
	private List<AssetVariable> m_assetVariables;

	public AssertVariableBasedPersistence() {
		setLogger(s_logger);
	}

	@Override
	public void init(CoreConfig coreConfig, AssertVariableBasedPersistenceConfig config, ServiceContext serviceContext)
		throws ConfigurationInitializationException {
		super.init(coreConfig, config, serviceContext);
		
		m_config = config;
		
		// 등록된 모든 AssetVariable들을 생성하고 활성화시킨다.
		m_lookup = MDTModelLookup.getInstance();
		m_assetVariables = FStream.from(m_config.getAssetVariableConfigs())
						 			.map(c -> createAssetVariable(c, m_lookup))
						 			.toList();
	}

	@Override
	public AssertVariableBasedPersistenceConfig asConfig() {
		return m_config;
	}
	
	private boolean isNormalModifier(QueryModifier modifier) {
        return modifier.getLevel() == Level.DEFAULT;
	}
	
	@Override
	public SubmodelElement getSubmodelElement(SubmodelElementIdentifier identifier, QueryModifier modifier)
		throws ResourceNotFoundException {
		// 주어진 identifier에 해당하는 AssetVariable을 찾는다.
		// 검색되면 해당 AssetVariable을 통해 외부 asset에서 SubmodelElement를 읽어오고,
		// 검색되지 않으면 m_basePersistence를 통해 SubmodelElement를 읽어온다.
		String submodelId = identifier.getSubmodelId();
		String submodelIdShort = m_lookup.getSubmodelIdShortFromSubmodelId(submodelId);
		String elementPath = identifier.getIdShortPath().toString();
		
		SubmodelElement baseSme = null;
		for ( AssetVariable var : m_assetVariables ) {
			ElementLocation varLoc = var.getElementLocation();
			
			// SubmodelIdShort이 다른 경우는 제외시킨다.
			if ( !submodelIdShort.equals(varLoc.getSubmodelIdShort()) ) {
				continue;
			}
			
			String varElmPath = varLoc.getElementPath();
			if ( elementPath.equals(varElmPath) ) {
				getLogger().debug("read AssetVariable: {}", var);
				return SubmodelUtils.duplicate(var.read());
			}
			else if ( elementPath.startsWith(varElmPath) ) {	// element < var
				// 요청한 elementPath가 AssetVariable의 elementPath의 일부인 경우
				// AssetVariable의 elementPath에서 요청한 부분만 추출한다.
				String relPath = SubmodelUtils.toRelativeIdShortPath(varElmPath, elementPath);
				SubmodelElement subPart = SubmodelUtils.traverse(var.read(), relPath);
				getLogger().debug("read AssetVariable: {}, sub-path={}", var, relPath);
				return SubmodelUtils.duplicate(subPart);
			}
			else if ( varElmPath.startsWith(elementPath) ) {	// element > var
				// AssetVariable을 통해 읽은 SubmodelElement가 요청한 elementPath의 일부인 경우
				// BasePersistence에서 대상 SubmodelElement을 읽어와서 AssetVariable 영역에
				// 해당하는 부분만을 갱신한다.
				if ( baseSme == null ) {
					baseSme = getBasePersistence().getSubmodelElement(identifier, QueryModifier.DEFAULT);
				}
				String relPath = SubmodelUtils.toRelativeIdShortPath(elementPath, varElmPath);
				SubmodelElement subPart = SubmodelUtils.traverse(baseSme, relPath);
				ElementValues.update(subPart, ElementValues.getValue(var.read()));
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
		throws ResourceNotFoundException {
		// 주어진 identifier에 해당하는 AssetVariable을 찾는다.
		// 검색되면 해당 AssetVariable을 통해 외부 asset을 갱신하고,
		// 검색되지 않으면 m_basePersistence를 통해 SubmodelElement를 갱신한다.
		String submodelId = identifier.getSubmodelId();
		String submodelIdShort = m_lookup.getSubmodelIdShortFromSubmodelId(submodelId);
		String elementPath = identifier.getIdShortPath().toString();
		
		for ( AssetVariable var : m_assetVariables ) {
			ElementLocation varLoc = var.getElementLocation();
			
			// SubmodelIdShort이 다른 경우는 제외시킨다.
			if ( !submodelIdShort.equals(varLoc.getSubmodelIdShort()) ) {
				continue;
			}
			
			String varElmPath = varLoc.getElementPath();
			if ( elementPath.equals(varElmPath) ) {
				var.update(update);
				getLogger().debug("update AssetVariable: {}", var);
			}
			else if ( elementPath.startsWith(varElmPath) ) {	// element < var
				// 갱신 영역이 AssetVariable의 영역의 일부인 경우
				// AssetVariable에서 SubmodelElement를 읽어와서 갱신된 부분을 갱신하고 다시 저장한다.
				String relPath = SubmodelUtils.toRelativeIdShortPath(varElmPath, elementPath);
				
				SubmodelElement assetElm = var.read();
				SubmodelElement subSme = SubmodelUtils.traverse(assetElm, relPath);
				ElementValues.update(subSme, ElementValues.getValue(update));
				var.update(assetElm);
				
				getLogger().debug("update AssetVariable: {}, sub-path={}", var, relPath);
			}
			else if ( varElmPath.startsWith(elementPath) ) {	// element > var
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
	public Submodel getSubmodel(String id, QueryModifier modifier) throws ResourceNotFoundException {
		Submodel submodel = getBasePersistence().getSubmodel(id, modifier);
		FStream.from(m_assetVariables)
                .filter(var -> id.equals(var.getElementLocation().getSubmodelId()))
                .forEach(var -> {
                	String elementPath = var.getElementLocation().getElementPath();
                	SubmodelElement buffer = SubmodelUtils.traverse(submodel, elementPath);
                	ElementValues.update(buffer, ElementValues.getValue(var.read()));
                });
		return submodel;
	}

	@Override
	public Page<Submodel> findSubmodels(SubmodelSearchCriteria criteria, QueryModifier modifier, PagingInfo paging) {
		Page<Submodel> page = getBasePersistence().findSubmodels(criteria, QueryModifier.DEFAULT, paging);
		List<Submodel> submodels = FStream.from(page.getContent())
											.mapOrIgnore(sm -> getSubmodel(sm.getId(), modifier))
									        .toList();
		
		if ( !isNormalModifier(modifier) ) {
			page.setContent(submodels);
			return page;
		}
		else {
			return getBasePersistence().findSubmodels(criteria, modifier, paging);
		}
	}

	@Override
	public void save(Submodel submodel) {
		getBasePersistence().save(submodel);

		String smId = submodel.getId();
		FStream.from(m_assetVariables)
                .filter(var -> smId.equals(var.getElementLocation().getSubmodelId()))
				.forEach(var -> {
					String elementPath = var.getElementLocation().getElementPath();
					SubmodelElement newSme = SubmodelUtils.traverse(submodel, elementPath);
					var.update(newSme);
				});
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
}
