package mdt.ksx9101;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@JsonIgnoreProperties(value = {"entityConfigs"})
public class GlobalPersistenceConfig {
	private JpaConfiguration jpaConfig;
}
