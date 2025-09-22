package mdt.persistence.asset.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;

import utils.KeyValue;
import utils.Throwables;
import utils.stream.FStream;

import mdt.model.sm.SubmodelUtils;
import mdt.persistence.MDTModelLookup;
import mdt.persistence.asset.AssetVariableException;
import mdt.persistence.asset.jdbc.MultiColumnCollectionAssetVariableConfig.ColumnToSubPath;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MultiColumnCollectionAssetVariable
										extends AbstractJdbcAssetVariable<MultiColumnCollectionAssetVariableConfig> {
	private Map<String,SubmodelElementHandler> m_memberHandlers;
	
	public MultiColumnCollectionAssetVariable(MultiColumnCollectionAssetVariableConfig config) {
		super(config);
	}

	@Override
	public void initialize(MDTModelLookup lookup) {
		super.initialize(lookup);

		m_memberHandlers = FStream.from(getConfig().getColumnToSubpathMapping())
									.mapToKeyValue(mapping -> {
										SubmodelElement member = SubmodelUtils.traverse(m_prototype,
																					mapping.getSubPath());
										SubmodelElementHandler handler = new SubmodelElementHandler(member);
										return KeyValue.of(mapping.getColumn(), handler);
									})
									.toMap();
	}

	@Override
	public boolean isReadable() {
		return getConfig().isReadable();
	}

	@Override
	public boolean isUpdatable() {
		return getConfig().isUpdatable();
	}

	// 레코드가 없는 경우 반복해서 경고를 출력하지 않도록 flag를 정의함
	private boolean m_noRecordWarningReported = false;
	@Override
	protected void loadOnBuffer(Connection conn) throws AssetVariableException {
		List<ColumnToSubPath> mappings = getConfig().getColumnToSubpathMapping();
		
		try ( Statement stmt = conn.createStatement(); ) {
			ResultSet rs = stmt.executeQuery(getConfig().getReadQuery());
			if ( rs.next() ) {
				// 다음번에 질의 결과가 없으면 다시 경고를 출력하도록 한다. 
				m_noRecordWarningReported = false;
				for ( ColumnToSubPath mapping : mappings ) {
					String column = mapping.getColumn();
					String subPath = mapping.getSubPath();
					
					Object value = rs.getObject(column);
					try {
						SubmodelElementHandler handler = m_memberHandlers.get(column);
						Property fieldSme = SubmodelUtils.traverse(m_prototype, subPath, Property.class);
						handler.updateWithJdbcObject(fieldSme, value);
					}
					catch ( Exception e ) {
						String msg = String.format("Failed to update field: subpath=%s, column=%s, value=%s, cause=%s",
													subPath, column, value, ""+e);
						throw new AssetVariableException(msg, e);
					}
				}
			}
			else {
				// 질의 결과가 없는 경우는 별다른 작업을 수행하지 않는다.
				if ( !m_noRecordWarningReported ) {
					getLogger().warn("Failed to read MultiColumnCollectionAssetVariable because "
										+ "no records for the variable: query={}",
										getConfig().getReadQuery());
					// 레코드가 없는 경우 반복해서 경고를 출력하지 않도록 한다.
					m_noRecordWarningReported = true;
				}
			}
		}
		catch ( SQLException e ) {
			String msg = String.format("Failed to read %s", this);
			throw new AssetVariableException(msg, e);
		}
	}

	@Override
	protected void saveBuffer(Connection conn) throws AssetVariableException {
		SubmodelElementCollection smc = (SubmodelElementCollection)m_prototype;
		
		try ( PreparedStatement pstmt = conn.prepareStatement(getConfig().getUpdateQuery()) ) {
			FStream.from(getConfig().getColumnToSubpathMapping())
					.zipWithIndex(1)
					.forEachOrThrow(idxed -> {
						ColumnToSubPath mapping = idxed.value();
						SubmodelElementHandler handler = m_memberHandlers.get(mapping.getColumn());
						SubmodelElement fieldSme = SubmodelUtils.traverse(smc, mapping.getSubPath());
						Object colValue = handler.toJdbcObject(fieldSme);
						pstmt.setObject(idxed.index(), colValue);
					});
			pstmt.execute();
		}
		catch ( Throwable e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			
			String msg = String.format("Failed to write %s with query: %s", this, getConfig().getUpdateQuery());
			throw new AssetVariableException(msg, cause);
		}
	}
}
