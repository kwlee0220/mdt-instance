package mdt.ksx9101;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JpaEntityContext extends ModelReferenceGenerator {
	private final EntityManagerFactory m_emFact;
	private final EntityManager m_em;
	
	public JpaEntityContext(EntityManagerFactory emFact, EntityManager em,
							String modelIdTemplate) {
		super(modelIdTemplate);
		
		m_emFact = emFact;
		m_em = em;
	}

	public EntityManagerFactory geEntityManagerFactory() {
		return m_emFact;
	}

	public EntityManager getEntityManager() {
		return m_em;
	}
}
