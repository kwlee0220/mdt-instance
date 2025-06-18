package mdt.ksx9101.jpa.push;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import lombok.Data;
import lombok.NoArgsConstructor;
import mdt.model.sm.SubmodelUtils;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
//@Entity
//@Table(name="aas_references")
@Data
@NoArgsConstructor
public class JpaReference {
	@Id @GeneratedValue(strategy=GenerationType.IDENTITY) private int id;
	private String type;
	
	@ElementCollection
	@CollectionTable(
		name="aas_keys",
		joinColumns = @JoinColumn(name="reference_id", referencedColumnName = "id"))
	@OrderColumn(name="key_seq")
	private List<JpaKey> keys = new ArrayList<>();
	
	public JpaReference(String type) {
		this.type = type;
	}
	
	public static JpaReference toSubmodelReference(String submodelId) {
		JpaReference ref = new JpaReference(ReferenceTypes.MODEL_REFERENCE.name());
		ref.setKeys(List.of(JpaKey.SUBMODEL(submodelId)));
		return ref;
	}
	
	public static JpaReference toSubmodelElementReference(String submodelId, String idShortPath) {
		JpaReference ref = new JpaReference(ReferenceTypes.MODEL_REFERENCE.name());
		
		ArrayList<JpaKey> keys = SubmodelUtils.parseIdShortPath(idShortPath)
										.map(seg -> JpaKey.SUBMODEL_ELEMENT(seg))
										.toList();
		keys.add(0, JpaKey.SUBMODEL(submodelId));
		ref.setKeys(keys);
		
		return ref;
	}
}
