package mdt.assetconnection.operation;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import okhttp3.RequestBody;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.func.FOption;
import utils.http.HttpRESTfulClient;

import mdt.model.MDTModelSerDe;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AIModelOperation extends AbstractJavaOperationProvider {
	public static final String CONFIG_GROUP_AIMODEL = "aiModelServers";
	private static final String ARG_MODEL_SERVER = "modelServer";
	private static final String ARG_MODEL_ID = "modelId";
	
	private final String m_serverUrl;
	private final String m_modelId;
	private final String m_servingUrl;
	private final HttpRESTfulClient m_restClient;

	public AIModelOperation(ServiceContext serviceContext, Reference operationRef,
							JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		super(serviceContext, operationRef, config);
		
		String serverKey = FOption.getOrElse(getStringArgument(ARG_MODEL_SERVER), "default");
		String serverUrl = FOption.ofNullable(getGlobalConfiguration(CONFIG_GROUP_AIMODEL, serverKey)
									.get("serverUrl"))
								.map(JsonNode::asText)
								.getOrNull();
		if ( serverUrl == null ) {
			String msg = String.format("Missing model server configuration for key '%s' in group '%s'",
										serverKey, CONFIG_GROUP_AIMODEL);
			throw new ConfigurationInitializationException(msg);
		}
		m_serverUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";

		m_modelId = getStringArgument(ARG_MODEL_ID);
		if ( m_modelId == null ) {
			throw new ConfigurationInitializationException("Missing argument 'modelId' in operation config: "
															+ config);
		}
		
		m_servingUrl = getServingUrl();
		m_restClient = HttpRESTfulClient.newDefaultClient();
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
							Map<String, SubmodelElement> inoutputVarList,
							Map<String, SubmodelElement> outputVarList) throws Exception {
		String samples = getRequiredStringVariableValue(inputVarList, "Samples");

		Property anomalyScoreProp = getRequiredPropertyVariable(outputVarList, "AnomalyScore",
																DataTypeDefXsd.DOUBLE);
		Property clusterProp = getRequiredPropertyVariable(outputVarList, "Cluster", DataTypeDefXsd.STRING);

		Map<String,Object> outputs = predict(m_servingUrl, samples);
		
		// 연산 수행 결과를 Output OperationVariable들에 반영한다.
		double anomalyScore = (Double)outputs.get("AnomalyScore");
		anomalyScoreProp.setValue("" + anomalyScore);
		String cluster = (String)outputs.get("Cluster");
		clusterProp.setValue(cluster);
	}
	
	private Map<String,Object> predict(String modelUrl, String samples)
		throws JsonProcessingException {
		Map<String,JsonNode> inputs = new HashMap<>();
		inputs.put("Samples", MDTModelSerDe.MAPPER.valueToTree(samples));
		
		var args = Map.of("Inputs", inputs);
		var argsJson = MDTModelSerDe.toJsonString(args);

		RequestBody reqBody = HttpRESTfulClient.newJsonRequestBody(argsJson);
		String respJson = m_restClient.post(modelUrl, reqBody);
		JsonNode outputs = MDTModelSerDe.MAPPER.readTree(respJson).get("Outputs");
		
		return Map.of(
			"AnomalyScore", outputs.get("AnomalyScore").doubleValue(),
			"Cluster", outputs.get("Cluster").asText()
		);
	}
	
	private String getServingUrl() {
		String encodedModelId = URLEncoder.encode(m_modelId, StandardCharsets.UTF_8);
		return m_serverUrl + encodedModelId;
	}
	
//	private String fetchModelUrl() {
//		String encodedModelId = URLEncoder.encode(m_modelId, StandardCharsets.UTF_8);
//		String modelUrl = m_serverUrl + encodedModelId;
//		String modelDescJson = m_restClient.get(modelUrl);
//		try {
//			JsonNode modelDesc = MDTModelSerDe.MAPPER.readTree(modelDescJson);
//			return modelDesc.get("data").get("directUrl").asText();
//		}
//		catch ( JsonProcessingException e ) {
//			throw new IllegalStateException("Failed to parse model description JSON: " + modelDescJson, e);
//		}
//	}
}
