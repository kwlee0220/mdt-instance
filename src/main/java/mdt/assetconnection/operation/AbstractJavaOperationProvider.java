package mdt.assetconnection.operation;

import java.util.Map;
import java.util.function.Function;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.exception.ConfigurationInitializationException;

import utils.Preconditions;
import utils.stream.FStream;

import mdt.MDTGlobalConfigurations;
import mdt.model.MDTModelSerDe;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class AbstractJavaOperationProvider implements OperationProvider {
	protected final Reference m_opRef;
	protected final JavaOperationProviderConfig m_config;
	protected final Map<String,JsonNode> m_args;
	
	abstract protected void invokeSync(Map<String,SubmodelElement> inputVarList,
										Map<String,SubmodelElement> inoutputVarList,
										Map<String,SubmodelElement> outputVarList) throws Exception;

	protected AbstractJavaOperationProvider(ServiceContext serviceContext, Reference operationRef,
									JavaOperationProviderConfig config) throws ConfigurationInitializationException {
		m_opRef = operationRef;
		m_config = config;
		m_args = config.getArguments();
	}

	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		var inputVarList = toVarMap(inputVars);
		var inoutputVarList = toVarMap(inoutputVars);
		var outputVarList = toVarMap(outputVars);
		
		invokeSync(inputVarList, inoutputVarList, outputVarList);
	}
	
	public JsonNode getGlobalConfiguration(String group, String configKey)
		throws ConfigurationInitializationException {
		if ( configKey.equals("default") ) {
			return MDTGlobalConfigurations.getConfig(group, configKey);
		}
		
		String configName = getStringArgument(configKey + "Name");
		if ( configName != null ) {
			return MDTGlobalConfigurations.getConfig(group, configName);
		}
		else {
			JsonNode jnode = getArgument(configKey);
			if ( jnode == null || !jnode.isObject() ) {
				String msg = String.format("Missing argument '%s' in operation config: %s", configKey, m_config);
				throw new ConfigurationInitializationException(msg);
			}
			return jnode;
		}
	}
	
	public <T> T getGlobalConfiguration(String group, String configKey, Class<T> configCls)
		throws ConfigurationInitializationException {
		JsonNode configNode = getGlobalConfiguration(group, configKey);
		return MDTModelSerDe.MAPPER.convertValue(configNode, configCls);
	}

	protected JsonNode getArgument(String argName) throws ConfigurationInitializationException {
		return m_args.get(argName);
	}
	protected JsonNode assertArgument(String argName) throws ConfigurationInitializationException {
		JsonNode jnode = getArgument(argName);
		if ( jnode == null ) {
			throw new ConfigurationInitializationException("Missing argument '" + argName
															+ "' in operation config: " + m_config);
		}
		return jnode;
	}
	
	protected String getStringArgument(String argName) throws ConfigurationInitializationException {
		JsonNode jnode = m_args.get(argName);
		if ( jnode != null ) {
			if ( !jnode.isTextual() ) {
				throw new ConfigurationInitializationException("Not string argument '" + argName);
			}
			return jnode.asText();
		}
		else {
			return null;
		}
	}
	protected String assertStringArgument(String argName) throws ConfigurationInitializationException {
		String str = getStringArgument(argName);
		if ( str == null ) {
			throw new ConfigurationInitializationException("Missing argument '" + argName
															+ "' in operation config: " + m_config);
		}
		return str;
	}
	
	protected Map<String,SubmodelElement> toVarMap(OperationVariable[] vars) {
		return FStream.of(vars)
						.map(OperationVariable::getValue)
						.tagKey(SubmodelElement::getIdShort)
						.toMap();
	}
	
	protected SubmodelElementCollection getRequiredSMCVariable(Map<String,SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		SubmodelElement arg = varList.get(varId);
		Preconditions.checkNotNullArgument(arg, String.format("Operation variable '%s' is missing", varId));
		Preconditions.checkArgument(arg instanceof SubmodelElementCollection smc,
								String.format("Operation variable '%s' is not a SubmodelElementCollection", varId));
		return (SubmodelElementCollection)arg;
	}
	protected SubmodelElementCollection getSMCVariable(Map<String,SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		SubmodelElement arg = varList.get(varId);
		Preconditions.checkArgument(arg == null || arg instanceof SubmodelElementCollection,
								String.format("Operation variable '%s' is not a SubmodelElementCollection", varId));
		return (SubmodelElementCollection)arg;
	}
	
	protected Property castToProperty(SubmodelElement sme, DataTypeDefXsd xsdType, String varId)
		throws IllegalArgumentException {
		Preconditions.checkArgument(sme instanceof Property idProp
									&& idProp.getValueType().equals(xsdType),
									String.format("Operation variable '%s' is not a %s Property", varId, xsdType));
		return (Property)sme;
	}
	
	protected Property getRequiredPropertyVariable(Map<String, SubmodelElement> varList, String varId,
													DataTypeDefXsd xsdType) throws IllegalArgumentException {
		SubmodelElement arg = varList.get(varId);
		Preconditions.checkNotNullArgument(arg, String.format("Operation variable '%s' is missing", varId));

		return castToProperty(arg, xsdType, varId);
	}
	protected Property getPropertyVariable(Map<String, SubmodelElement> varList, String varId,
											DataTypeDefXsd xsdType) throws IllegalArgumentException {
		SubmodelElement arg = varList.get(varId);
		return arg != null ? castToProperty(arg, xsdType, varId) : null;
	}
	
	protected String getRequiredStringVariableValue(Map<String, SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		Property prop = getRequiredPropertyVariable(varList, varId, DataTypeDefXsd.STRING);
		return prop.getValue();
	}
	// 숫자형 getRequired*VariableValue()는 변수가 없거나 값이 비어 있으면 IllegalArgumentException을 던진다.
	protected int getRequiredIntVariableValue(Map<String, SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		return parseRequiredValue(varList, varId, DataTypeDefXsd.INT, Integer::valueOf);
	}
	protected float getRequiredFloatVariableValue(Map<String, SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		return parseRequiredValue(varList, varId, DataTypeDefXsd.FLOAT, Float::valueOf);
	}
	protected double getRequiredDoubleVariableValue(Map<String, SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		return parseRequiredValue(varList, varId, DataTypeDefXsd.DOUBLE, Double::valueOf);
	}
	
	// 아래의 get*VariableValue()는 선택 변수용이다. 변수가 없으면 null을 반환하고,
	// 숫자형은 값이 비어 있는 경우(null 또는 공백 문자열)도 지정되지 않은 것으로 보아 null을 반환한다.
	protected String getStringVariableValue(Map<String, SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		Property prop = getPropertyVariable(varList, varId, DataTypeDefXsd.STRING);
		return prop != null ? prop.getValue() : null;
	}
	protected Integer getIntVariableValue(Map<String, SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		return parseOptionalValue(varList, varId, DataTypeDefXsd.INT, Integer::valueOf);
	}
	protected Float getFloatVariableValue(Map<String, SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		return parseOptionalValue(varList, varId, DataTypeDefXsd.FLOAT, Float::valueOf);
	}
	protected Double getDoubleVariableValue(Map<String, SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		return parseOptionalValue(varList, varId, DataTypeDefXsd.DOUBLE, Double::valueOf);
	}
	protected Boolean getBooleanVariableValue(Map<String, SubmodelElement> varList, String varId)
		throws IllegalArgumentException {
		return parseOptionalValue(varList, varId, DataTypeDefXsd.BOOLEAN, AbstractJavaOperationProvider::parseBoolean);
	}

	// Boolean.valueOf()는 "true" 외의 모든 값을 false로 읽어 오타를 감추므로, xs:boolean의 어휘만 허용한다.
	private static Boolean parseBoolean(String str) {
		return switch ( str.toLowerCase() ) {
			case "true", "1" -> true;
			case "false", "0" -> false;
			default -> throw new IllegalArgumentException("not a boolean: " + str);
		};
	}

	private <T> T parseRequiredValue(Map<String, SubmodelElement> varList, String varId,
									DataTypeDefXsd xsdType, Function<String,T> parser)
		throws IllegalArgumentException {
		Property prop = getRequiredPropertyVariable(varList, varId, xsdType);
		T value = parseValue(prop, varId, xsdType, parser);
		Preconditions.checkArgument(value != null, String.format("Operation variable '%s' has no value", varId));
		return value;
	}

	private <T> T parseOptionalValue(Map<String, SubmodelElement> varList, String varId,
									DataTypeDefXsd xsdType, Function<String,T> parser)
		throws IllegalArgumentException {
		Property prop = getPropertyVariable(varList, varId, xsdType);
		return prop != null ? parseValue(prop, varId, xsdType, parser) : null;
	}

	private static <T> T parseValue(Property prop, String varId, DataTypeDefXsd xsdType,
									Function<String,T> parser) throws IllegalArgumentException {
		String value = prop.getValue();
		if ( value == null || value.isBlank() ) {
			return null;
		}
		try {
			return parser.apply(value.trim());
		}
		catch ( IllegalArgumentException e ) {		// NumberFormatException 포함
			throw new IllegalArgumentException(String.format("Operation variable '%s' has an invalid %s value: %s",
																varId, xsdType, value), e);
		}
	}
	
//	protected Property assertStringVariable(Map<String, SubmodelElement> varList, String varId)
//		throws IllegalArgumentException {
//		SubmodelElement arg = varList.get(varId);
//		Preconditions.checkNotNullArgument(arg, String.format("Output variable '%s' is missing", varId));
//		
//		return castToProperty(arg, DataTypeDefXsd.STRING, varId);
//	}
//	
//	protected Property assertIntVariable(Map<String, SubmodelElement> varList, String varId)
//		throws IllegalArgumentException {
//		SubmodelElement arg = varList.get(varId);
//		Preconditions.checkNotNullArgument(arg, String.format("Output variable '%s' is missing", varId));
//		
//		return castToProperty(arg, DataTypeDefXsd.INT, varId);
//	}
//	
//	protected Property assertFloatVariable(Map<String, SubmodelElement> varList, String varId)
//		throws IllegalArgumentException {
//		SubmodelElement arg = varList.get(varId);
//		Preconditions.checkNotNullArgument(arg, String.format("Output variable '%s' is missing", varId));
//		
//		return castToProperty(arg, DataTypeDefXsd.FLOAT, varId);
//	}
//	
//	protected Property assertDoubleVariable(Map<String, SubmodelElement> varList, String varId)
//		throws IllegalArgumentException {
//		SubmodelElement arg = varList.get(varId);
//		Preconditions.checkNotNullArgument(arg, String.format("Output variable '%s' is missing", varId));
//		
//		return castToProperty(arg, DataTypeDefXsd.DOUBLE, varId);
//	}
	
	
	
	
	
//	protected Float getFloatVariable(Map<String, SubmodelElement> varList, String varId)
//		throws IllegalArgumentException {
//		SubmodelElement arg = varList.get(varId);
//		if ( arg == null ) {
//			return null;
//		}
//		
//		Preconditions.checkArgument(arg instanceof Property prop
//									&& prop.getValueType().equals(DataTypeDefXsd.FLOAT),
//									String.format("Output variable '%s' is not a Float Property", varId));
//		return Float.parseFloat(((Property)arg).getValue());
//	}
}
