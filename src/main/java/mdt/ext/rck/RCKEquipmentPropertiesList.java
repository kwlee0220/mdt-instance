package mdt.ext.rck;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import utils.KeyValue;
import utils.Preconditions;
import utils.json.JacksonDeserializationException;
import utils.json.JacksonUtils;

import mdt.model.sm.value.ElementCollectionValue;


/**
 * {@code facility} 메시지의 설비별 속성. 설비와 속성의 순서는 메시지의 순서를 따르며, 만든 뒤에는 바뀌지 않는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class RCKEquipmentPropertiesList {
	private static final String FIELD_NAME = "Name";
	private static final String FIELD_INDEX = "Index";
	// 숫자만으로는 AAS idShort가 될 수 없어(영문자로 시작해야 한다) 설비 번호 앞에 붙인다.
	private static final String INDEX_KEY_PREFIX = "Index";
	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
	
	private final Map<RCKEquipment,Map<String,Number>> m_equipmentProperties;
	
	private RCKEquipmentPropertiesList(Map<RCKEquipment,Map<String,Number>> equipmentProperties) {
		Preconditions.checkNotNullArgument(equipmentProperties, "equipmentProperties is null");
		
		m_equipmentProperties = equipmentProperties;
	}
	
	/**
	 * {@code facility} 메시지의 {@code message} 내용을 파싱한다. 빈 객체는 설비가 없는 정상 입력으로 본다.
	 *
	 * @param message	{@code message} 내용.
	 * @return	설비 속성.
	 * @throws JacksonDeserializationException	{@code message}가 JSON 객체가 아니거나({@code null} 포함),
	 * 										설비 항목이 JSON 객체가 아니거나, {@code Name}(JSON 문자열)이나
	 * 										{@code Index}({@code int} 범위의 JSON 정수)가 없거나 형식이 맞지 않는 경우,
	 * 										숫자가 아닌 속성이 있는 경우, 또는 같은 설비(그룹, {@code Name},
	 * 										{@code Index})가 두 번 나오는 경우.
	 */
	public static RCKEquipmentPropertiesList parse(JsonNode message) throws JacksonDeserializationException {
		// properties()는 객체가 아닌 노드에서 빈 집합을 돌려주므로, 검사하지 않으면 빈 결과가 정상처럼 나간다.
		if ( message == null || !message.isObject() ) {
			throw new JacksonDeserializationException("facility message is not a JSON object: " + message);
		}
		
		LinkedHashMap<RCKEquipment,Map<String,Number>> map = new LinkedHashMap<>();
		for ( Entry<String,JsonNode> groupEntry: message.properties() ) {
			for ( KeyValue<RCKEquipment,Map<String,Number>> kv: parseGroup(groupEntry) ) {
				if ( map.putIfAbsent(kv.key(), kv.value()) != null ) {
					throw new JacksonDeserializationException("duplicate equipment: " + kv.key());
				}
			}
		}
		return new RCKEquipmentPropertiesList(map);
	}
	
	/**
	 * 설비 목록을 메시지의 순서대로 반환한다.
	 *
	 * @return	변경할 수 없는 설비 목록.
	 */
	public List<RCKEquipment> equipments() {
		return List.copyOf(m_equipmentProperties.keySet());
	}
	
	/**
	 * 주어진 설비의 속성을 메시지의 순서대로 반환한다.
	 *
	 * @param equipment	설비.
	 * @return	변경할 수 없는 속성 맵.
	 * @throws IllegalArgumentException	{@code equipment}가 {@code null}이거나 이 객체에 없는 설비인 경우.
	 */
	public Map<String,Number> propertiesOf(RCKEquipment equipment) {
		Preconditions.checkNotNullArgument(equipment, "equipment is null");
		Map<String,Number> properties = m_equipmentProperties.get(equipment);
		Preconditions.checkArgument(properties != null, "unknown equipment: %s", equipment);
		return Collections.unmodifiableMap(properties);
	}
	
	/**
	 * {@code overrides}의 속성 값으로 이 목록의 속성 값을 바꾼 새 목록을 반환한다. 이 객체는 바뀌지 않는다.
	 * <p>
	 * 설비는 그룹, {@code Name}, {@code Index}가 모두 같아야 같은 설비로 본다. 설비와 속성의 순서는 이 목록을 따른다.
	 * 이 목록에 없는 설비나, 있는 설비라도 이 목록에 없는 속성은 바꾸거나 추가하지 않고 경고 로그만 남긴다.
	 *
	 * @param overrides	바꿀 설비 속성.
	 * @param logger	무시한 설비·속성을 알릴 로거.
	 * @return	속성 값을 바꾼 새 목록.
	 */
	RCKEquipmentPropertiesList overriddenBy(RCKEquipmentPropertiesList overrides, Logger logger) {
		Preconditions.checkNotNullArgument(overrides, "overrides is null");
		Preconditions.checkNotNullArgument(logger, "logger is null");

		LinkedHashMap<RCKEquipment,Map<String,Number>> map = new LinkedHashMap<>();
		m_equipmentProperties.forEach((equipment, props) -> map.put(equipment, new LinkedHashMap<>(props)));
		overrides.m_equipmentProperties.forEach((equipment, props) -> {
			Map<String,Number> target = map.get(equipment);
			if ( target == null ) {
				logger.warn("ignoring equipment override: no such equipment from the simulator: {}", equipment);
				return;
			}
			props.forEach((name, value) -> {
				if ( target.containsKey(name) ) {
					target.put(name, value);
				}
				else {
					logger.warn("ignoring equipment override: no such property from the simulator: "
								+ "equipment={}, property={}", equipment, name);
				}
			});
		});
		return new RCKEquipmentPropertiesList(map);
	}

	/**
	 * {@link #parse(JsonNode)}의 역변환: 설비 속성을 입력과 같은 형식의 JSON으로 만든다.
	 * <p>
	 * 설비는 그룹별로 묶고, 그룹과 속성은 입력 순서를 유지한다. 각 설비는 {@code Name}, {@code Index}, 나머지 속성
	 * 순서로 쓴다. 한 그룹에 설비가 하나면 객체로, 여럿이면 배열로 쓴다. 입력의 형태를 따로 기억하지 않으므로, 원소가
	 * 하나뿐인 배열로 들어온 입력은 객체로 나오고, 빈 배열로 들어온 그룹은 설비가 없어 나오지 않는다.
	 *
	 * @return	설비 속성 JSON.
	 */
	public ObjectNode toJsonNode() {
		Map<String,List<ObjectNode>> groups = new LinkedHashMap<>();
		m_equipmentProperties.forEach((equipment, props) -> {
			ObjectNode node = NODES.objectNode();
			node.put(FIELD_NAME, equipment.name());
			node.put(FIELD_INDEX, equipment.index());
			putProperties(node, props);
			groups.computeIfAbsent(equipment.group(), group -> new ArrayList<>()).add(node);
		});
		
		ObjectNode top = NODES.objectNode();
		groups.forEach((group, nodes) -> {
			if ( nodes.size() == 1 ) {
				top.set(group, nodes.get(0));
			}
			else {
				top.putArray(group).addAll(nodes);
			}
		});
		return top;
	}
	
	/**
	 * 설비 속성을 {@code 그룹 → Index<번호> → 속성} 구조로 묶어 {@code proto}에 맞는 값으로 변환한다.
	 * <p>
	 * {@code Index}가 1인 설비의 키는 {@code Index1}이 되고, {@code Name}은 들어가지 않는다. 설비가 하나뿐인 그룹도
	 * 같은 구조로 묶는다.
	 * <pre>{@code
	 * { "Press": { "Index1": { "Time": 45, "PressTime": 1 }, "Index2": { "Time": 45, "PressTime": 1 } } }
	 * }</pre>
	 * 결과의 구조와 순서는 {@code proto}를 따르며, 메시지의 순서는 유지되지 않는다. {@code proto}에 없는 그룹, 설비,
	 * 속성은 오류 없이 빠지고, 설비 속성에 없는 {@code proto}의 필드는 결과에 들어가지 않는다.
	 *
	 * @param proto	결과의 구조와 각 속성의 타입을 정하는 값.
	 * @return	설비 속성 값.
	 * @throws IOException	설비 속성이 {@code proto}의 구조나 타입과 맞지 않는 경우.
	 * @throws IllegalArgumentException	{@code proto}가 {@code null}인 경우.
	 * @throws IllegalStateException	한 그룹에 {@code Name}만 다르고 {@code Index}가 같은 설비가 있어 키가 겹치는 경우.
	 */
	public ElementCollectionValue toElementCollectionValue(ElementCollectionValue proto) throws IOException {
		Preconditions.checkNotNullArgument(proto, "proto is null");
		
		ObjectNode top = NODES.objectNode();
		m_equipmentProperties.forEach((equipment, props) -> {
			ObjectNode groupNode = top.withObjectProperty(equipment.group());
			String indexKey = INDEX_KEY_PREFIX + equipment.index();
			if ( groupNode.has(indexKey) ) {
				throw new IllegalStateException("duplicate equipment index: group=" + equipment.group()
												+ ", index=" + equipment.index());
			}
			putProperties(groupNode.putObject(indexKey), props);
		});
		return ElementCollectionValue.parseValueJsonNode(top, proto);
	}
	
	@Override
	public String toString() {
		return toJsonNode().toString();
	}
	
	private static void putProperties(ObjectNode node, Map<String,Number> properties) {
		properties.forEach((name, value) -> node.set(name, JacksonUtils.MAPPER.valueToTree(value)));
	}
	
	private static List<KeyValue<RCKEquipment,Map<String,Number>>> parseGroup(Entry<String,JsonNode> groupEntry)
		throws JacksonDeserializationException {
		String group = groupEntry.getKey();
		JsonNode groupNode = groupEntry.getValue();
		
		if ( groupNode.isArray() ) {
			List<KeyValue<RCKEquipment,Map<String,Number>>> equipments = new ArrayList<>();
			for ( JsonNode jn: groupNode ) {
				equipments.add(parseEquipment(group, jn));
			}
			return equipments;
		}
		else {
			return List.of(parseEquipment(group, groupNode));
		}
	}
	
	private static KeyValue<RCKEquipment,Map<String,Number>> parseEquipment(String group, JsonNode jnode)
		throws JacksonDeserializationException {
		String name = JacksonUtils.getTextField(jnode, FIELD_NAME);
		int index = JacksonUtils.getIntField(jnode, FIELD_INDEX);
		RCKEquipment equipment = new RCKEquipment(group, name, index);
		
		Map<String,Number> properties = parseProperties(equipment, jnode);
		
		return KeyValue.of(equipment, properties);
	}
	
	private static Map<String,Number> parseProperties(RCKEquipment equipment, JsonNode jnode)
		throws JacksonDeserializationException {
		// 속성 순서를 유지한다(toJsonNode).
		Map<String,Number> properties = new LinkedHashMap<>();
		for ( Entry<String,JsonNode> ent: jnode.properties() ) {
			String name = ent.getKey();
			if ( !name.equals(FIELD_NAME) && !name.equals(FIELD_INDEX) ) {
				JsonNode value = ent.getValue();
				if ( !value.isNumber() ) {
					throw new JacksonDeserializationException("non-numeric property: equipment=" + equipment
																+ ", property=" + name + ", value=" + value);
				}
				properties.put(name, value.numberValue());
			}
		}
		return properties;
	}
}
