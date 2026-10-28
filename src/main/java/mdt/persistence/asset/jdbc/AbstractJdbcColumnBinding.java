package mdt.persistence.asset.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import mdt.model.ResourceNotFoundException;
import mdt.model.sm.SubmodelUtils;
import mdt.persistence.asset.AssetVariableException;


/**
 * {@link JdbcColumnBinding} 구현들이 공유하는 대상 요소 탐색과 오류 메시지 생성.
 *
 * @author Kang-Woo Lee (ETRI)
 */
abstract class AbstractJdbcColumnBinding implements JdbcColumnBinding {
	private final String m_subPath;

	protected AbstractJdbcColumnBinding(String subPath) {
		m_subPath = subPath;
	}

	@Override
	public String getSubPath() {
		return m_subPath;
	}

	/**
	 * {@code root} 아래의 대상 요소를 반환한다.
	 *
	 * @throws AssetVariableException	대상 요소가 없는 경우.
	 */
	protected SubmodelElement getTarget(SubmodelElement root) {
		return traverse(root, m_subPath);
	}

	/**
	 * 컬럼 값을 반영하지 못한 경우의 예외를 만든다. 가능하면 컬럼 이름도 포함한다.
	 */
	protected AssetVariableException readFailure(ResultSet rs, int column, Object value, Throwable cause) {
		String label;
		try {
			label = rs.getMetaData().getColumnLabel(column);
		}
		catch ( SQLException | RuntimeException e ) {
			label = "?";
		}
		String msg = String.format("Failed to read column into SubmodelElement: subPath=%s, column=%d(%s), value=%s",
									m_subPath, column, label, value);
		return new AssetVariableException(msg, cause);
	}

	/**
	 * 요소 값을 질의 파라미터로 바꾸지 못한 경우의 예외를 만든다.
	 */
	protected AssetVariableException bindFailure(int index, Object value, Throwable cause) {
		String msg = String.format("Failed to bind SubmodelElement to parameter: subPath=%s, index=%d, value=%s",
									m_subPath, index, value);
		return new AssetVariableException(msg, cause);
	}

	@Override
	public String toString() {
		return String.format("%s[subPath=%s]", getClass().getSimpleName(), m_subPath);
	}

	static SubmodelElement traverse(SubmodelElement root, String subPath) {
		if ( subPath.isEmpty() ) {
			return root;
		}
		try {
			return SubmodelUtils.traverse(root, subPath);
		}
		catch ( ResourceNotFoundException e ) {
			throw new AssetVariableException("SubmodelElement not found: subPath=" + subPath, e);
		}
	}
}
