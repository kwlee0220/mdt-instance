package mdt.ksx9101.jpa.push;

import java.util.Map;

import org.hibernate.jpa.HibernatePersistenceProvider;

import com.google.common.collect.Maps;

import utils.jdbc.JdbcConfiguration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import mdt.ksx9101.jpa.JpaPersistenceUnitInfo;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TestEnvers {
	public static final void main(String... args) throws Exception {
		Map<String,String> props = Maps.newHashMap();
		props.put("hibernate.show_sql", "true");
		props.put("hibernate.format_sql", "true");
		props.put("hibernate.hbm2ddl.auto", "update");
		
		JdbcConfiguration jdbcConfig = new JdbcConfiguration();
		jdbcConfig.setJdbcUrl("jdbc:postgresql://localhost:5432/mdt");
		jdbcConfig.setUser("mdt");
		jdbcConfig.setPassword("urc2004");
		
		JpaPersistenceUnitInfo postgres = new JpaPersistenceUnitInfo("mdt-ksx9101", jdbcConfig);
		EntityManagerFactory emf = new HibernatePersistenceProvider()
										.createContainerEntityManagerFactory(postgres, props);
		
		try ( EntityManager em = emf.createEntityManager() ) {
//			AuditReader reader = AuditReaderFactory.get(em);
//			
//			EntityTransaction tx = em.getTransaction();
//			tx.begin();
//			
//			Phone rev = reader.find(Phone.class, 0, 52);
//			System.out.println(rev);
//			
//			for ( Number revNo: reader.getRevisions(Phone.class, 0) ) {
//				System.out.println(revNo);
//			}
//			
//			Date date1 = reader.getRevisionDate(1);
//			System.out.println(date1);
//			
//			Date date2 = reader.getRevisionDate(2);
//			System.out.println(date2);
//			
//			Date date3 = reader.getRevisionDate(52);
//			System.out.println(date3);
			
//			Phone phone = em.find(Phone.class, 0);
//			System.out.println(phone);
//			
//			phone.setNumber("010-3333-1111");
			
//			Phone phone2 = new Phone();
//			phone2.setToken("token_001");
//			phone2.setNumber("010-8888-9999");
//			em.persist(phone2);
			
//			tx.commit();
		}
		emf.close();
	}
}
