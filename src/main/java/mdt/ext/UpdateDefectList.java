package mdt.ext;

import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import utils.stream.FStream;

import mdt.assetconnection.operation.JavaOperationProviderConfig;
import mdt.assetconnection.operation.OperationProvider;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class UpdateDefectList implements OperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(UpdateDefectList.class);
	
	public UpdateDefectList(ServiceContext serviceContext, Reference opRef, JavaOperationProviderConfig config) {
		if ( s_logger.isInfoEnabled() ) {
			IdShortPath idShortPath = IdShortPath.fromReference(opRef);
			s_logger.info("AssetConnection (Operation) is ready: op-ref={}", idShortPath);
		}
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		Map<String, SubmodelElement> inputVarList = FStream.of(inputVars)
															.map(OperationVariable::getValue)
															.tagKey(SubmodelElement::getIdShort)
															.toMap();
		Map<String, SubmodelElement> inoutputVarList = FStream.of(inoutputVars)
																.map(OperationVariable::getValue)
																.tagKey(SubmodelElement::getIdShort)
																.toMap();
		
		SubmodelElement defectProp = inputVarList.get("Defect");
		Preconditions.checkArgument(defectProp != null, "Input argument is missing: 'Defect'");
		Preconditions.checkArgument(defectProp instanceof Property, "Argument 'Defect' is not Property");
		String defect = ((Property)inputVarList.get("Defect")).getValue();
		
		SubmodelElement defectListProp = inoutputVarList.get("DefectList");
		Preconditions.checkArgument(defectProp != null, "Inoutput argument is missing: 'DefectList'");
		Preconditions.checkArgument(defectProp instanceof Property, "Argument 'DefectList' is not Property");
		String defectList = ((Property)defectListProp).getValue();
		
		String updateDefectList = update(defect, defectList);
		((Property)defectListProp).setValue(updateDefectList);
	}
	
	private String update(String defect, String defectList) {
		int isDefect = FStream.of(defect.split(",")).exists(s -> !s.equals("0")) ? 1 : 0;

		List<Integer> window = FStream.of(defectList.trim().split(","))
										.map(s -> s.equals("1") ? 1 : 0)
										.concatWith(isDefect)
										.takeLast(10);
		return FStream.from(window).join(',');
		
	}
	
//	public static void main(String... args) throws Exception {
//		String result;
//		UpdateDefectList updateList = new UpdateDefectList();
//		
//		result = updateList.update("0,0,0,0,0,0,0,0,0", "0,0,1");
//		Preconditions.checkState(result.equals("0,0,1,0"), result);
//		
//		result = updateList.update("0,0,0,0,0,0,1,0,0", "0,0,1");
//		Preconditions.checkState(result.equals("0,0,1,1"), result);
//		
//		result = updateList.update("0,0,0,0,0,0,1,0,0", "0,0,1,0,0,0,0,1,1");
//		Preconditions.checkState(result.equals("0,0,1,0,0,0,0,1,1,1"), result);
//		
//		result = updateList.update("0,0,0,0,0,0,0,0,0", "0,0,1,0,0,0,0,1,1,0");
//		Preconditions.checkState(result.equals("0,1,0,0,0,0,1,1,0,0"), result);
//	}
}
