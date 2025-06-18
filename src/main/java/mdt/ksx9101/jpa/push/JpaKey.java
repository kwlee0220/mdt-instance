package mdt.ksx9101.jpa.push;

import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;

import jakarta.persistence.Embeddable;
import lombok.Data;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Embeddable
@Data
public class JpaKey {
	private String type;
	private String value;
	
	public static JpaKey SUBMODEL_ELEMENT(String id) {
		JpaKey key = new JpaKey();
		key.setType(KeyTypes.SUBMODEL_ELEMENT.name());
		key.setValue(id);
		
		return key;
	}
	
	public static JpaKey SUBMODEL(String id) {
		JpaKey key = new JpaKey();
		key.setType(KeyTypes.SUBMODEL.name());
		key.setValue(id);
		
		return key;
	}
}
