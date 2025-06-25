package mdt.assetconnection.operation;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetOperationProviderConfig;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class MDTOperationProviderConfig implements AssetOperationProviderConfig {
	private @Nullable JavaOperationProviderConfig java;
	private @Nullable ProgramOperationProviderConfig program;
	private @Nullable HttpOperationProviderConfig http;
}
