package mdt.ksx9101;

import jakarta.persistence.EntityManager;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaEntityLoader<T> {
	public T load(EntityManager em, Object key);
}
