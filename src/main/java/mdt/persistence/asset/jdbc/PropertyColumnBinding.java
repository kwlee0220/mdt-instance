package mdt.persistence.asset.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import mdt.aas.DataType;
import mdt.persistence.asset.AssetVariableException;


/**
 * 컬럼 값을 Property의 valueType에 맞게 변환하는 {@link JdbcColumnBinding}.
 * <p>
 * 읽을 때는 JDBC 드라이버가 돌려준 객체를 {@link DataType#fromJdbcObject(Object)}로 변환한 뒤 값 문자열로
 * 반영하고, 쓸 때는 값 문자열을 {@link DataType#toJdbcObject(Object)}로 변환해 파라미터로 설정한다.
 * SQL {@code NULL}과 Property 값 {@code null}은 서로 대응한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
final class PropertyColumnBinding extends AbstractJdbcColumnBinding {
	@SuppressWarnings("rawtypes")
	private final DataType m_type;

	PropertyColumnBinding(String subPath, DataType<?> type) {
		super(subPath);

		m_type = type;
	}

	@Override
	public void read(ResultSet rs, int column, SubmodelElement root) throws SQLException {
		Property prop = getTargetProperty(root);
		Object jdbcObj = rs.getObject(column);
		try {
			// 숫자 타입의 변환은 null을 0으로 바꾸므로 null은 따로 처리한다.
			prop.setValue((jdbcObj != null) ? m_type.toValueString(m_type.fromJdbcObject(jdbcObj)) : null);
		}
		catch ( RuntimeException e ) {
			throw readFailure(rs, column, jdbcObj, e);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void bind(PreparedStatement ps, int index, SubmodelElement root) throws SQLException {
		Property prop = getTargetProperty(root);
		Object jdbcObj;
		try {
			jdbcObj = m_type.toJdbcObject(m_type.parseValueString(prop.getValue()));
		}
		catch ( RuntimeException e ) {
			throw bindFailure(index, prop.getValue(), e);
		}
		ps.setObject(index, jdbcObj);
	}

	@Override
	public void clear(SubmodelElement root) {
		getTargetProperty(root).setValue(null);
	}

	private Property getTargetProperty(SubmodelElement root) {
		SubmodelElement target = getTarget(root);
		if ( target instanceof Property prop ) {
			return prop;
		}
		throw new AssetVariableException(String.format("Not a Property: subPath=%s, element=%s",
														getSubPath(), target.getClass().getSimpleName()));
	}
}
