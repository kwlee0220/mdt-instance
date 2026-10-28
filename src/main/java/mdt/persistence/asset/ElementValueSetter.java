package mdt.persistence.asset;

import java.io.IOException;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.jetbrains.annotations.Nullable;

import mdt.aas.DataType;
import mdt.aas.DataTypes;
import mdt.model.MDTModelSerDe;
import mdt.model.sm.value.ElementValues;


/**
 * 외부 데이터소스(OPC UA 등)에서 읽은 Java 값을 SubmodelElement에 반영한다.
 * <p>
 * 생성 시 주어진 SubmodelElement의 종류로 변환 방식을 정한다.
 * <ul>
 *   <li>{@link Property} — 값을 선언된 valueType의 {@link DataType}에 맞는 문자열로 바꿔 넣는다
 *       (Java 객체를 너그럽게 변환한다). 값이 {@code null}이면 Property의 값도 {@code null}이 된다.</li>
 *   <li>그 밖의 SubmodelElement, 또는 {@link DataTypes}가 지원하지 않는 valueType의 Property — 값을 MDT value
 *       형식의 JSON 문자열({@code toString()})로 보고 반영한다. 값이 {@code null}이면 바꾸지 않는다.</li>
 * </ul>
 * JDBC 컬럼과의 변환은 {@code mdt.persistence.asset.jdbc.JdbcColumnBinding}을 사용한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ElementValueSetter {
	@SuppressWarnings("rawtypes")
	private final @Nullable DataType m_type;

	/**
	 * 주어진 SubmodelElement의 종류에 맞는 변환기를 생성한다.
	 *
	 * @param prototype	값을 반영할 SubmodelElement(또는 같은 구조의 prototype).
	 */
	public ElementValueSetter(SubmodelElement prototype) {
		m_type = (prototype instanceof Property prop) ? DataTypes.fromAas4jDatatype(prop.getValueType()) : null;
	}

	/**
	 * 값을 SubmodelElement에 반영한다.
	 *
	 * @param element	값을 반영할 SubmodelElement.
	 * @param value		반영할 값.
	 * @throws AssetVariableException	변환이 실패한 경우.
	 */
	public void update(SubmodelElement element, @Nullable Object value) {
		try {
			if ( m_type != null ) {
				// 숫자 타입의 변환은 null을 0으로 바꾸므로 null은 따로 처리한다.
				((Property)element).setValue((value != null) ? m_type.toValueString(value) : null);
			}
			else if ( value != null ) {
				ElementValues.update(element, MDTModelSerDe.readJsonNode(value.toString()));
			}
		}
		catch ( IOException | RuntimeException e ) {
			String msg = String.format("Failed to update %s with value=%s", element.getIdShort(), value);
			throw new AssetVariableException(msg, e);
		}
	}
}
