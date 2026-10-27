package mdt.assetconnection.operation;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import com.fasterxml.jackson.databind.JsonNode;

import okhttp3.RequestBody;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.func.FOption;
import utils.http.HttpRESTfulClient;
import utils.stream.FStream;
import utils.stream.KeyValueFStream;

import mdt.model.MDTModelSerDe;
import mdt.model.sm.value.ElementValues;


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
	private final String m_version;
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
		m_version = getStringArgument("version");
		
		m_servingUrl = buildServingUrl();
		m_restClient = HttpRESTfulClient.newDefaultClient();
	}

	@Override
	protected void invokeSync(Map<String, SubmodelElement> inputVarList,
							Map<String, SubmodelElement> inoutputVarList,
							Map<String, SubmodelElement> outputVarList) throws Exception {
		Map<String,JsonNode> inputs = new HashMap<>();
		KeyValueFStream.from(inputVarList)
						.filterValue(v -> v != null)
						.forEach((varName, varSme) -> {
							if ( varSme instanceof Property prop ) {
								Object value = prop.getValue();
								if ( value != null ) {
									inputs.put(varName, MDTModelSerDe.MAPPER.valueToTree(value));
								}
							}
							else {
								throw new IllegalArgumentException("Unsupported SubmodelElement type: " + varSme);
							}
						});

		var args = Map.of("Inputs", inputs);
		var argsJson = MDTModelSerDe.toJsonString(args);

		RequestBody reqBody = HttpRESTfulClient.newJsonRequestBody(argsJson);
		String respJson = m_restClient.post(m_servingUrl, reqBody);
		
		JsonNode respNode = MDTModelSerDe.MAPPER.readTree(respJson);
		JsonNode outputs = FStream.from(respNode.fields())
								.findFirst(field -> field.getKey().equalsIgnoreCase("Outputs"))
								.map(Map.Entry::getValue)
								.getOrThrow(() -> new IllegalStateException("Missing 'Outputs' field in the response: "
															+ respJson));
		
		for ( Map.Entry<String,SubmodelElement> ent: outputVarList.entrySet() ) {
			String varName = ent.getKey();
			SubmodelElement valueSme = ent.getValue();
			
			JsonNode outVar = outputs.get(varName);
			if ( outVar == null ) {
				continue;
			}
			
			ElementValues.update(valueSme, outVar);
		}
	}
	
	private String buildServingUrl() {
		String encodedModelId = URLEncoder.encode(m_modelId, StandardCharsets.UTF_8);
		String url = m_serverUrl + encodedModelId;
		if ( m_version != null ) {
			url += "/" + URLEncoder.encode(m_version, StandardCharsets.UTF_8);;
		}
		
		return url;
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
