package mdt.persistence.asset.opcua;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Throwables;

import mdt.model.sm.SubmodelUtils;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;
import mdt.persistence.asset.ElementValueSetter;

import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;


/**
 * OPC-UA 서버의 노드 하나를 SubmodelElement 하나에 연결하는 읽기 전용 {@code AssetVariable}.
 * <p>
 * 접속할 서버는 전역 설정의 OPC-UA 설정({@code opcuas.default})을 따르고, 읽을 노드는 설정의
 * {@code nodePath}를 namespace 2의 문자열 identifier로 사용한다({@code ns=2;s=<nodePath>}).
 * 읽은 값은 연결된 SubmodelElement의 타입에 맞게 변환된다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OpcUaAssetVariable extends AbstractOpcUaAssetVariable<OpcUaAssetVariableConfig> {
	private static final Logger s_logger = LoggerFactory.getLogger(OpcUaAssetVariable.class);

	private ElementValueSetter m_valueSetter;

	public OpcUaAssetVariable(OpcUaAssetVariableConfig config) throws ConfigurationInitializationException {
		super(config);

		setLogger(s_logger);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);

		m_valueSetter = new ElementValueSetter(m_prototype);
	}

	@Override
	public boolean isReadable() {
		return getConfig().isReadable();
	}

	@Override
	public SubmodelElement read() throws AssetVariableException {
		String nodePath = getConfig().getNodePath();
		Object value = readNodeValue(new NodeId(2, nodePath));
		try {
			// 동시에 호출된 read()끼리 간섭하지 않도록 공유 prototype은 고치지 않고
			// 복사본에 값을 반영해 반환한다.
			SubmodelElement result = SubmodelUtils.duplicate(m_prototype);
			m_valueSetter.update(result, value);

			return result;
		}
		catch ( Exception e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			String msg = String.format("Failed to update %s with OPC-UA node value: path=%s, value=%s, cause=%s",
										getElementLocation(), nodePath, value, cause);
			throw new AssetVariableException(msg, cause);
		}
	}
}
