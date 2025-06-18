package mdt.ksx9101;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.List;

import org.apache.commons.beanutils.PropertyUtils;

import com.google.common.base.Preconditions;

import utils.InternalException;
import utils.jdbc.SQLDataType;
import utils.jdbc.crud.DaoList;
import utils.jdbc.crud.JdbcCRUDOperation;
import utils.jdbc.crud.JdbcDaoListCRUDOperation;
import utils.jdbc.crud.JdbcSQLCRUDOperation;
import utils.jdbc.crud.TableBinding;
import utils.jdbc.crud.TableBinding.ColumnBinding;
import utils.jdbc.crud.TableBindingCRUDOperation;
import utils.stream.FStream;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class LatestSQLCRUDOperation<T> implements JdbcSQLCRUDOperation<T> {
	private final TableBinding m_binding;
	private final String m_tsColumn;
	private final List<String> m_groupByColumns;
	private final JdbcCRUDOperation<T> m_daoCrud;
	
	private LatestSQLCRUDOperation(TableBinding binding, String tsColumn,
									List<String> groupByColumns,
									JdbcCRUDOperation<T> daoCrud) {
		Preconditions.checkArgument(binding != null, "TableBinding was null");
		Preconditions.checkArgument(daoCrud != null, "JdbcCRUDOperation was null");
		
		m_binding = binding;
		m_tsColumn = tsColumn;
		m_groupByColumns = groupByColumns;
		m_daoCrud = daoCrud;
	}
	
	public static <T> LatestSQLCRUDOperation<T> from(TableBinding binding, String tsColumn) {
		return new LatestSQLCRUDOperation<>(binding, tsColumn, binding.getKeyColumns(),
											new TableBindingCRUDOperation<>(binding));
	}
	
	public static <T, L extends DaoList<T>>
	LatestSQLCRUDOperation<L> newListCRUDOperation(TableBinding binding, List<String> keyColumns,
													String tsColumn) {
		TableBinding daoListBinding = new TableBinding();
		daoListBinding.setTableName(binding.getTableName());
		daoListBinding.setColumnBindings(binding.getColumnBindings());
		daoListBinding.setKeyColumns(keyColumns);
		
		TableBindingCRUDOperation<T> daoOp = new TableBindingCRUDOperation<>(binding);
		JdbcDaoListCRUDOperation<T,L> daoListOp = new JdbcDaoListCRUDOperation<>(daoOp);
		
		return new LatestSQLCRUDOperation<>(daoListBinding, 
											tsColumn,
											binding.getKeyColumns(),
											daoListOp);
	}

	private static final String SQL_INNER_SELECT_FORMAT
		= "select {0}, max({1}) as {1} from {2} where {3} group by {0}";
	private static final String SQL_SELECT_FORMAT
		= "select {0} from {1} v inner join ({2}) latest "
				+ "on {3} "
		+ "where {4}";
	@Override
	public int read(Connection conn, T key) throws SQLException {
		Preconditions.checkArgument(conn != null, "Connection was null");
		Preconditions.checkArgument(key != null, "Key was null");

		String groupByColsCsv = FStream.from(m_groupByColumns).join(", ");
		String keyColsCsv = FStream.from(m_binding.getKeyColumns()).join(", ");
		String whereClause = FStream.from(m_binding.getKeyColumns())
									.map(n -> String.format("%s = ?", n))
									.join(" and ");
		String innerSelectSql = MessageFormat.format(SQL_INNER_SELECT_FORMAT,
														groupByColsCsv, m_tsColumn,
														m_binding.getTableName(), whereClause);
		
		String joinCondExpr = FStream.from(m_groupByColumns).concatWith(m_tsColumn)
										.map(n -> String.format("v.%s = latest.%s", n, n))
										.join(" and ");
		
		String selectColumnsCsv = FStream.from(m_binding.getColumnNames())
										.map(n -> String.format("v.%s", n))
										.join(',');
		String outerWhereClause = FStream.from(m_binding.getKeyColumns())
										.map(n -> String.format("v.%s = ?", n))
										.join(" and ");
		String sql = MessageFormat.format(SQL_SELECT_FORMAT,
											selectColumnsCsv,
											m_binding.getTableName(),
											innerSelectSql,
											joinCondExpr,
											outerWhereClause);
		try ( PreparedStatement pstmt = conn.prepareStatement(sql) ) {
			int nkeys = m_binding.getKeyColumnBindings().size();
			fillPreparedStatement(pstmt, key, 1, m_binding.getKeyColumnBindings());
			fillPreparedStatement(pstmt, key, nkeys+1, m_binding.getKeyColumnBindings());
			
			try ( ResultSet rset = pstmt.executeQuery() ) {
				return m_daoCrud.read(key, rset);
			}
		}
	}
	
	static final String SQL_INSERT_FORMAT = "insert into %s(%s) values %s";
	@Override
	public int insert(Connection conn, T dao) throws SQLException {
		Preconditions.checkArgument(conn != null, "Connection was null");
		Preconditions.checkArgument(dao != null, "Dao was null");
		
		String valuesStr = FStream.from(m_binding.getColumnBindings()).map(v -> "?").join(",", "(", ")");
		String sql = String.format(SQL_INSERT_FORMAT, m_binding.getTableName(),
									m_binding.getColumnNamesCsv(), valuesStr);
		try ( PreparedStatement pstmt = conn.prepareStatement(sql) ) {
			return m_daoCrud.insert(dao, pstmt);
		}
	}
	
	static final String SQL_UPDATE_FORMAT = "update %s set %s where %s";
	@Override
	public int update(Connection conn, T dao) throws SQLException {
		Preconditions.checkArgument(conn != null, "Connection was null");
		Preconditions.checkArgument(dao != null, "Parameter was null");
		
		String whereClause = FStream.from(m_binding.getKeyColumns())
									.map(n -> String.format("%s = ?", n))
									.join(" and ");
		String setColumnListStr = FStream.from(m_binding.getNonKeyColumnBindings())
												.drop(2)
												.map(b -> String.format("%s = ?", b.getColumnName()))
												.join(',');
		String sql = String.format(SQL_UPDATE_FORMAT, m_binding.getTableName(), setColumnListStr, whereClause);
		
		try ( PreparedStatement pstmt = conn.prepareStatement(sql) ) {
			int cnt = m_daoCrud.update(dao, pstmt);
			if ( cnt == 0 ) {
				return insert(conn, dao);
			}
			else {
				return cnt;
			}
		}
	}
	
	static final String SQL_DELETE = "delete from %s where %s";
	@Override
	public int delete(Connection conn, T key) throws SQLException {
		Preconditions.checkArgument(conn != null, "Connection was null");
		Preconditions.checkArgument(key != null, "Key was null");
		
		String whereClause = FStream.from(m_binding.getKeyColumns())
									.map(n -> String.format("%s = ?", n))
									.join(" and ");
		String sql = String.format(SQL_DELETE, m_binding.getTableName(), whereClause);
		try ( PreparedStatement pstmt = conn.prepareStatement(sql) ) {
			return m_daoCrud.delete(key, pstmt);
		}
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void fillPreparedStatement(PreparedStatement pstmt, Object dao, int startColIdx,
										List<ColumnBinding> columnBindings) throws SQLException {
		for ( int colIdx = 0; colIdx < columnBindings.size(); ++colIdx ) {
			ColumnBinding colBinding = columnBindings.get(colIdx);
			
			try {
				Object value = PropertyUtils.getSimpleProperty(dao, colBinding.getDaoFieldName());
				
				SQLDataType sqlType = colBinding.getSqlType();
				sqlType.fillPreparedStatementWithJavaValue(pstmt, startColIdx+colIdx, value);
			}
			catch ( SQLException e ) {
				throw e;
			}
			catch ( Exception e ) {
				String msg = String.format("Failed to set field: dao=%s, field=%s",
											dao, colBinding.getDaoFieldName());
				throw new InternalException(msg);
			}
		}
	}
}
