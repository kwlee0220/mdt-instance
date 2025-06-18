package mdt.ksx9101.jpa.push;

import java.util.List;

import com.google.common.collect.Lists;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import lombok.Data;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
//@Entity
//@Table(name="asset_administration_shells")
@Data
public class JpaAssetAdministrationShells {
	@Id private String id;
	private String idShort;
	
	@Embedded
	@AttributeOverrides({
		@AttributeOverride(name="assetKind", column=@Column(name="info_assetKind")),
		@AttributeOverride(name="globalAssetId", column=@Column(name="info_globalAssetId")),
		@AttributeOverride(name="assetType", column=@Column(name="info_assetType"))
	})
	private JpaAssetInformation assetInformation;

	@ElementCollection
	@CollectionTable(
		name="aas_references",
		joinColumns = @JoinColumn(name="reference_id", referencedColumnName = "id"))
	@OrderColumn(name="key_seq")
	private List<JpaReference> submodelReferences = Lists.newArrayList();
}
