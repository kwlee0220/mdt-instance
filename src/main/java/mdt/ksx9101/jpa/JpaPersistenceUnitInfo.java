package mdt.ksx9101.jpa;

import java.net.URL;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;

import utils.func.Optionals;
import utils.jdbc.JdbcConfiguration;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JpaPersistenceUnitInfo implements PersistenceUnitInfo {
	private static final List<String> ENTITY_CLASSE_NAMES = List.of(
		JpaAndon.class.getName(),
		JpaRepair.class.getName(),
		JpaItemMaster.class.getName(),
		JpaBOM.class.getName(),
		JpaProductionPlanning.class.getName(),
		JpaProductionOrder.class.getName(),
		JpaProductionPerformance.class.getName(),
		JpaRouting.class.getName(),
		JpaEquipment.class.getName(),
		JpaEquipmentParameter.class.getName(),
		JpaEquipmentParameterValue.class.getName(),
		JpaOperation.class.getName(),
		JpaOperationParameter.class.getName(),
		JpaOperationParameterValue.class.getName(),
		JpaEquipments.class.getName(),
//		JpaLOT.class.getName(),
		JpaLine.class.getName()
	);
	
	private final String m_punitName;
	private final JdbcConfiguration m_jdbcConf;
	
	public JpaPersistenceUnitInfo(String punitName, JdbcConfiguration jdbcConf) {
		m_punitName = punitName;
		m_jdbcConf = jdbcConf;
	}

	@Override
	public String getPersistenceUnitName() {
		return m_punitName;
	}

	@Override
	public String getPersistenceProviderClassName() {
		return "org.hibernate.jpa.HibernatePersistenceProvider";
	}

	@Override
	public PersistenceUnitTransactionType getTransactionType() {
		return PersistenceUnitTransactionType.RESOURCE_LOCAL;
	}

	@Override
	public Properties getProperties() {
		Properties props = new Properties();
		props.put("hibernate.show_sql", "false");
		props.put("hibernate.format_sql", "false");
		props.put("hibernate.hbm2ddl.auto", "validate");
		return props;
	}

	@Override
	public DataSource getJtaDataSource() {
		if ( m_jdbcConf.getMaxPoolSize() > 0 ) {
			HikariConfig hikariConfig = new HikariConfig();
			hikariConfig.setJdbcUrl(m_jdbcConf.getJdbcUrl());
			hikariConfig.setUsername(m_jdbcConf.getUser());
			hikariConfig.setPassword(m_jdbcConf.getPassword());
			hikariConfig.setMaximumPoolSize(m_jdbcConf.getMaxPoolSize());
			return new HikariDataSource(hikariConfig);
		}
		else {
			DriverManagerDataSource dataSrc = new DriverManagerDataSource();
			
			Optionals.accept(m_jdbcConf.getDriverClassName(), dataSrc::setDriverClassName);
			dataSrc.setUrl(m_jdbcConf.getJdbcUrl());
			dataSrc.setUsername(m_jdbcConf.getUser());
			dataSrc.setPassword(m_jdbcConf.getPassword());
			
			return dataSrc;
		}
	}

	@Override
	public List<String> getManagedClassNames() {
		return ENTITY_CLASSE_NAMES;
	}

	@Override
	public DataSource getNonJtaDataSource() {
		return null;
	}

	@Override
	public List<String> getMappingFileNames() {
		return null;
	}

	@Override
	public List<URL> getJarFileUrls() {
		return null;
	}

	@Override
	public URL getPersistenceUnitRootUrl() {
		return null;
	}

	@Override
	public boolean excludeUnlistedClasses() {
		return false;
	}

	@Override
	public SharedCacheMode getSharedCacheMode() {
		return null;
	}

	@Override
	public ValidationMode getValidationMode() {
		return null;
	}

	@Override
	public String getPersistenceXMLSchemaVersion() {
		return null;
	}

	@Override
	public ClassLoader getClassLoader() {
		return null;
	}

	@Override
	public void addTransformer(ClassTransformer transformer) {
	}

	@Override
	public ClassLoader getNewTempClassLoader() {
		return null;
	}

}
