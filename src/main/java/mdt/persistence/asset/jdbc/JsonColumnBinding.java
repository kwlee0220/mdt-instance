package mdt.persistence.asset.jdbc;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import mdt.model.MDTModelSerDe;
import mdt.model.sm.value.ElementValues;


/**
 * 컬럼 값을 MDT value 형식의 JSON 문자열로 주고받는 {@link JdbcColumnBinding}.
 * <p>
 * SubmodelElementCollection 등 Property가 아닌 요소와, valueType을 {@link mdt.aas.DataTypes}가 지원하지 않는
 * Property에 쓰인다. 컬럼은 {@link ResultSet#getString(int)}으로 읽으므로 문자열 컬럼뿐 아니라 CLOB이나
 * PostgreSQL {@code json}/{@code jsonb} 컬럼도 읽을 수 있다. SQL {@code NULL}이면 요소를 바꾸지 않는다.
 * 쓸 때는 읽을 때와 같은 값 형식(타입 정보 {@code @type} 없음)의 JSON 문자열을 문자열 파라미터로 설정한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
final class JsonColumnBinding extends AbstractJdbcColumnBinding {
	JsonColumnBinding(String subPath) {
		super(subPath);
	}

	@Override
	public void read(ResultSet rs, int column, SubmodelElement root) throws SQLException {
		SubmodelElement target = getTarget(root);
		String json = rs.getString(column);
		if ( json == null ) {
			return;
		}
		try {
			ElementValues.update(target, MDTModelSerDe.readJsonNode(json));
		}
		catch ( IOException | RuntimeException e ) {
			throw readFailure(rs, column, json, e);
		}
	}

	@Override
	public void bind(PreparedStatement ps, int index, SubmodelElement root) throws SQLException {
		SubmodelElement target = getTarget(root);
		String json;
		try {
			// 읽을 때와 같은 형식이 되도록 타입 정보(@type) 없이 값 부분만 쓴다.
			json = ElementValues.getValue(target).toValueJsonString();
		}
		catch ( RuntimeException e ) {
			throw bindFailure(index, target.getIdShort(), e);
		}
		ps.setString(index, json);
	}

	@Override
	public boolean isClearable() {
		return false;
	}

	@Override
	public void clear(SubmodelElement root) {
		// JSON으로 주고받는 요소는 비울 값의 형태가 정해져 있지 않으므로 바꾸지 않는다.
		getTarget(root);
	}
}
