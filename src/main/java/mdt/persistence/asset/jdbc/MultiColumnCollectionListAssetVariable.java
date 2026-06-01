package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;

import utils.jdbc.SQLDataType;
import utils.jdbc.SQLDataTypes;
import utils.stream.FStream;

import mdt.aas.DataType;
import mdt.aas.DataTypes;
import mdt.model.sm.SubmodelUtils;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiColumnCollectionListAssetVariable
							extends AbstractJdbcAssetVariable<MultiColumnCollectionListAssetVariableConfig> {
	private final List<DataType<?>> m_memberTypes = new ArrayList<>();
	
	public MultiColumnCollectionListAssetVariable(MultiColumnCollectionListAssetVariableConfig config) {
		super(config);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);
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
	protected void loadOnBuffer(Connection conn) throws AssetVariableException {
		List<ColumnToSubPath> mappings = getConfig().getMemberColumnToSubPathMappings();

		SubmodelElementList elmList = (SubmodelElementList)m_prototype;
		elmList.getValue().clear();
		
		List<SubmodelElementCollection> members = new ArrayList<>();
		try ( Statement stmt = conn.createStatement();
			ResultSet rs = stmt.executeQuery(getConfig().getReadQuery()) ) {
			while ( rs.next() ) {
				SubmodelElementCollection member = readRow(rs, mappings);
				members.add(member);
				elmList.getValue().add(member);
			}
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read %s", this);
			throw new AssetVariableException(msg, e);
		}
	}
	
	private SubmodelElementCollection readRow(ResultSet rs, List<ColumnToSubPath> mappings)
																				throws SQLException {
		List<SubmodelElement> cols = FStream.zip(mappings, getMemberTypes(rs))
											.mapOrThrow(tup -> readColumnElement(rs, tup._1(), tup._2()))
											.toList();
		return SubmodelUtils.newSubmodelElementCollection("", cols);
	}
	
	private SubmodelElement readColumnElement(ResultSet rs, ColumnToSubPath mapping, DataType<?> dtype)
																						throws SQLException {
		Object jdbcObj = rs.getObject(mapping.getColumnName());
		String value = ( jdbcObj != null ) ? dtype.fromJdbcObject(jdbcObj).toString() : null;
		return (SubmodelElement)SubmodelUtils.newProperty(mapping.getSubPath(), value,
															dtype.getTypeDefXsd());
	}

	@Override
	protected void saveBuffer(Connection conn) throws AssetVariableException {
	}
	
	private List<DataType<?>> getMemberTypes(ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		if ( m_memberTypes.isEmpty() ) {
			for ( int i =0; i < meta.getColumnCount(); ++i ) {
				int colTypeCode = meta.getColumnType(i+1);
				SQLDataType<?,?> sqlType = SQLDataTypes.fromSqlType(colTypeCode);
				DataType<?> dtype = DataTypes.fromJavaClass(sqlType.getJavaClass());
				m_memberTypes.add(dtype);
			}
		}
		return m_memberTypes;
	}
}
