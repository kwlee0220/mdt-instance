package mdt.assetconnection;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetConnectionException;
import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AssetOperationProvider;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class UnsupportedOperationProvider implements AssetOperationProvider {
	public OperationVariable[] invoke(OperationVariable[] input, OperationVariable[] inoutput)
			throws AssetConnectionException {
		throw new UnsupportedOperationException();
	}
}
