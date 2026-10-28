package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;
import org.jetbrains.annotations.Nullable;

import utils.jdbc.SQLDataTypes;
import utils.stream.FStream;

import mdt.aas.DataType;
import mdt.aas.DataTypes;
import mdt.model.sm.SubmodelUtils;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;


/**
 * 조회 결과의 각 행을 SubmodelElementList의 원소(SubmodelElementCollection) 하나로 만드는 읽기 전용
 * {@link AbstractJdbcAssetVariable} 구현.
 * <p>
 * 원소의 구조는 다음과 같이 정한다.
 * <ul>
 *   <li>모델의 SubmodelElementList에 원소가 하나 이상 있으면, 첫 원소를 템플릿으로 삼아 행마다 복제한 뒤
 *       매핑된 하위 element에 컬럼 값을 채운다. 값의 타입은 모델에 선언된 valueType을 따르며,
 *       매핑되지 않은 하위 element는 템플릿의 값을 그대로 가진다. 중첩된 subPath도 쓸 수 있다.</li>
 *   <li>원소가 없으면, 조회할 때마다 결과 컬럼의 SQL 타입으로 임시 템플릿을 만든다. 임시 템플릿은 매핑마다
 *       subPath를 idShort로 하는 Property를 가지며, valueType은 컬럼의 SQL 타입으로 정한다(대응하는 타입이
 *       없으면 {@code xs:string}). 이 경우 subPath는 중첩될 수 없다.</li>
 * </ul>
 * 두 경우 모두 템플릿을 행마다 복제하고 {@link JdbcColumnBinding}으로 컬럼 값을 채우므로 값 변환 규칙이 같다.
 * 리스트 원소에는 idShort를 두지 않는다. 컬럼은 이름이 아니라 조회 질의의 {@code SELECT} 목록 위치로 읽는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiColumnCollectionListAssetVariable
							extends AbstractJdbcAssetVariable<MultiColumnCollectionListAssetVariableConfig> {
	private @Nullable SubmodelElement m_template;		// 모델에 정의된 행 템플릿
	private @Nullable List<JdbcColumnBinding> m_templateBindings;	// 매핑 순서

	public MultiColumnCollectionListAssetVariable(MultiColumnCollectionListAssetVariableConfig config) {
		super(config);
	}

	/**
	 * 바인딩된 SubmodelElementList의 첫 원소가 있으면 그것을 행 템플릿으로 준비한다.
	 *
	 * @throws AssetVariableException	대상이 SubmodelElementList가 아니거나, 템플릿 없이 중첩 subPath를 쓴 경우.
	 */
	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);

		if ( !(m_prototype instanceof SubmodelElementList sml) ) {
			throw new AssetVariableException(String.format("%s requires a SubmodelElementList: %s",
															getClass().getSimpleName(), getElementLocation()));
		}
		List<ColumnToSubPath> mappings = getConfig().getMemberColumnToSubPathMappings();
		if ( !sml.getValue().isEmpty() ) {
			m_template = SubmodelUtils.duplicate(sml.getValue().get(0));
			m_templateBindings = createBindings(m_template, mappings);
		}
		else {
			for ( ColumnToSubPath mapping: mappings ) {
				if ( mapping.getSubPath().contains(".") ) {
					throw new AssetVariableException(String.format(
							"nested subPath requires a template element in the model's list: element=%s, subPath=%s",
							getElementLocation(), mapping.getSubPath()));
				}
			}
		}
	}

	@Override
	public boolean isReadable() {
		return getConfig().isReadable();
	}

	@Override
	public boolean isUpdatable() {
		return getConfig().isUpdatable();
	}

	@Override
	protected void loadOnBuffer(Connection conn, SubmodelElement buffer) throws AssetVariableException {
		List<SubmodelElement> rows = ((SubmodelElementList)buffer).getValue();
		rows.clear();

		try ( Statement stmt = conn.createStatement();
			ResultSet rs = stmt.executeQuery(getConfig().getReadQuery()) ) {
			SubmodelElement template = m_template;
			List<JdbcColumnBinding> bindings = m_templateBindings;
			if ( template == null ) {
				List<ColumnToSubPath> mappings = getConfig().getMemberColumnToSubPathMappings();
				template = buildTemplate(rs.getMetaData(), mappings);
				bindings = createBindings(template, mappings);
			}

			while ( rs.next() ) {
				SubmodelElement row = SubmodelUtils.duplicate(template);
				for ( int i = 0; i < bindings.size(); ++i ) {
					bindings.get(i).read(rs, i + 1, row);
				}
				rows.add(row);
			}
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read %s", this);
			throw new AssetVariableException(msg, e);
		}
	}

	/** 이 변수는 읽기 전용이다. */
	@Override
	protected void saveBuffer(Connection conn, SubmodelElement value) throws AssetVariableException {
		throw new AssetVariableException("read-only AssetVariable: " + this);
	}

	private static List<JdbcColumnBinding> createBindings(SubmodelElement template, List<ColumnToSubPath> mappings) {
		return FStream.from(mappings)
						.map(mapping -> JdbcColumnBinding.of(template, mapping.getSubPath()))
						.toList();
	}

	/**
	 * 모델에 템플릿 원소가 없을 때 쓸 임시 템플릿을 조회 결과 컬럼의 SQL 타입으로 만든다.
	 * <p>
	 * 매핑마다 subPath를 idShort로 하는 값 없는 Property를 두며, valueType은 컬럼의 SQL 타입에 대응하는
	 * 데이터 타입이다. 대응하는 타입이 없으면 {@code xs:string}으로 다룬다. SubmodelElementList의 원소는
	 * idShort를 갖지 않는다.
	 */
	private static SubmodelElement buildTemplate(ResultSetMetaData meta, List<ColumnToSubPath> mappings)
		throws SQLException {
		List<SubmodelElement> props = new ArrayList<>();
		for ( int i = 0; i < mappings.size(); ++i ) {
			DataType<?> dtype = getColumnType(meta, i + 1);
			props.add(SubmodelUtils.newProperty(mappings.get(i).getSubPath(), null, dtype.getTypeDefXsd()));
		}
		return SubmodelUtils.newSubmodelElementCollection(null, props);
	}

	private static DataType<?> getColumnType(ResultSetMetaData meta, int column) throws SQLException {
		DataType<?> dtype;
		try {
			dtype = DataTypes.fromJavaClass(SQLDataTypes.fromSqlType(meta.getColumnType(column)).getJavaClass());
		}
		catch ( IllegalArgumentException e ) {
			dtype = null;
		}
		return (dtype != null) ? dtype : DataTypes.STRING;
	}
}
