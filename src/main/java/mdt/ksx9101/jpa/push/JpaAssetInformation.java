package mdt.ksx9101.jpa.push;

import jakarta.persistence.Embeddable;
import lombok.Data;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Embeddable
@Data
public class JpaAssetInformation {
	private String assetKind;
	private String globalAssetId;
	private String assetType;
}
