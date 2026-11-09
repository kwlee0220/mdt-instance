package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import utils.json.JacksonDeserializationException;

import mdt.model.sm.value.ElementCollectionValue;
import mdt.model.sm.value.ElementListValue;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.PropertyValue;


/**
 * {@link RCKEquipmentProperties}의 파싱과 역변환을 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKEquipmentPropertiesTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	public void sampleMessageRoundTripsIncludingOrder() throws Exception {
		JsonNode message = MAPPER.readTree(new File("misc/facility_press.json")).path("message");
		// 문자열로 비교한다(JsonNode.equals는 객체 키 순서를 보지 않는다).
		assertEquals(message.toString(), RCKEquipmentProperties.parse(message).toJsonNode().toString());
	}

	/**
	 * 주어진 JSON과 구조와 타입이 같고 숫자 값은 모두 0인 proto를 만든다. 결과의 값이 proto가 아니라 메시지에서 왔음을 확인할 수 있다.
	 */
	private static ElementValue protoOf(JsonNode node) {
		if ( node.isObject() ) {
			LinkedHashMap<String,ElementValue> fields = new LinkedHashMap<>();
			node.properties().forEach(ent -> fields.put(ent.getKey(), protoOf(ent.getValue())));
			return new ElementCollectionValue(fields);
		}
		return node.isInt() ? PropertyValue.INTEGER(0)
							: node.isLong() ? PropertyValue.LONG(0L) : PropertyValue.DOUBLE(0d);
	}

	private static String toElementCollectionValueJson(String message, String protoJson) throws Exception {
		RCKEquipmentProperties props = RCKEquipmentProperties.parse(MAPPER.readTree(message));
		ElementCollectionValue proto = (ElementCollectionValue)protoOf(MAPPER.readTree(protoJson));
		return props.toElementCollectionValue(proto).toValueJsonNode().toString();
	}

	@Test
	public void sampleMessageConvertsToElementCollectionValueOfPropertiesFile() throws Exception {
		JsonNode message = MAPPER.readTree(new File("misc/facility_press.json")).path("message");
		String expected = MAPPER.readTree(new File("misc/properties.json")).toString();
		assertEquals(expected, toElementCollectionValueJson(message.toString(), expected));
	}

	@Test
	public void toElementCollectionValueGroupsByIndexIncludingOrder() throws Exception {
		String json = "{\"Press\":[{\"Name\":\"p\",\"Index\":2,\"Time\":45,\"ClampTime\":2},"
					+ "{\"Name\":\"p\",\"Index\":1,\"Time\":46,\"ClampTime\":3}],"
					+ "\"Conveyor\":{\"Name\":\"c\",\"Index\":1,\"InputTime\":10}}";
		String expected = "{\"Press\":{\"Index2\":{\"Time\":45,\"ClampTime\":2},\"Index1\":{\"Time\":46,\"ClampTime\":3}},"
						+ "\"Conveyor\":{\"Index1\":{\"InputTime\":10}}}";
		// 문자열로 비교한다(JsonNode.equals는 객체 키 순서를 보지 않는다).
		assertEquals(expected, toElementCollectionValueJson(json, expected));
	}

	@Test
	public void toElementCollectionValueKeepsGroupStructureForSingleEquipment() throws Exception {
		String json = "{\"A\":{\"Name\":\"a\",\"Index\":3,\"Time\":1.5,\"Big\":5000000000}}";
		String expected = "{\"A\":{\"Index3\":{\"Time\":1.5,\"Big\":5000000000}}}";
		assertEquals(expected, toElementCollectionValueJson(json, expected));
	}

	@Test
	public void toElementCollectionValueRejectsSameIndexWithDifferentNames() throws Exception {
		String json = "{\"A\":[{\"Name\":\"a\",\"Index\":1},{\"Name\":\"b\",\"Index\":1}]}";
		RCKEquipmentProperties props = RCKEquipmentProperties.parse(MAPPER.readTree(json));
		ElementCollectionValue proto = new ElementCollectionValue(new LinkedHashMap<>());
		assertThrows(IllegalStateException.class, () -> props.toElementCollectionValue(proto));
	}

	@Test
	public void toElementCollectionValueRejectsNullProto() throws Exception {
		RCKEquipmentProperties props = RCKEquipmentProperties.parse(MAPPER.readTree("{}"));
		assertThrows(IllegalArgumentException.class, () -> props.toElementCollectionValue(null));
	}

	@Test
	public void toElementCollectionValueFollowsProtoOrder() throws Exception {
		String json = "{\"A\":[{\"Name\":\"a\",\"Index\":1,\"Time\":1,\"Clamp\":2},"
					+ "{\"Name\":\"a\",\"Index\":2,\"Time\":3,\"Clamp\":4}],"
					+ "\"B\":{\"Name\":\"b\",\"Index\":1,\"Time\":5}}";
		String proto = "{\"B\":{\"Index1\":{\"Time\":0}},"
					+ "\"A\":{\"Index2\":{\"Clamp\":0,\"Time\":0},\"Index1\":{\"Clamp\":0,\"Time\":0}}}";
		assertEquals("{\"B\":{\"Index1\":{\"Time\":5}},"
					+ "\"A\":{\"Index2\":{\"Clamp\":4,\"Time\":3},\"Index1\":{\"Clamp\":2,\"Time\":1}}}",
					toElementCollectionValueJson(json, proto));
	}

	@Test
	public void toElementCollectionValueDropsWhatProtoDoesNotHave() throws Exception {
		String json = "{\"A\":[{\"Name\":\"a\",\"Index\":1,\"Time\":1,\"Extra\":2},"
					+ "{\"Name\":\"a\",\"Index\":2,\"Time\":3}],"
					+ "\"B\":{\"Name\":\"b\",\"Index\":1,\"Time\":4}}";
		String proto = "{\"A\":{\"Index1\":{\"Time\":0}}}";
		assertEquals("{\"A\":{\"Index1\":{\"Time\":1}}}", toElementCollectionValueJson(json, proto));
	}

	@Test
	public void toElementCollectionValueOmitsProtoFieldsMissingFromMessage() throws Exception {
		String json = "{\"A\":{\"Name\":\"a\",\"Index\":1,\"Time\":1}}";
		String proto = "{\"A\":{\"Index1\":{\"Time\":0,\"Clamp\":0},\"Index2\":{\"Time\":0}},"
					+ "\"B\":{\"Index1\":{\"Time\":0}}}";
		assertEquals("{\"A\":{\"Index1\":{\"Time\":1}}}", toElementCollectionValueJson(json, proto));
	}

	@Test
	public void toElementCollectionValueRejectsListInsideProto() throws Exception {
		String json = "{\"A\":{\"Name\":\"a\",\"Index\":1,\"Time\":1}}";
		RCKEquipmentProperties props = RCKEquipmentProperties.parse(MAPPER.readTree(json));

		LinkedHashMap<String,ElementValue> fields = new LinkedHashMap<>();
		fields.put("A", new ElementListValue(List.of()));
		ElementCollectionValue proto = new ElementCollectionValue(fields);
		assertThrows(IOException.class, () -> props.toElementCollectionValue(proto));
	}

	@Test
	public void toElementCollectionValueRejectsFractionForIntProperty() throws Exception {
		String json = "{\"A\":{\"Name\":\"a\",\"Index\":1,\"Time\":1.5}}";
		String proto = "{\"A\":{\"Index1\":{\"Time\":0}}}";
		assertThrows(IOException.class, () -> toElementCollectionValueJson(json, proto));
	}

	@Test
	public void nonIntegerNumberTypesRoundTrip() throws Exception {
		String json = "{\"A\":{\"Name\":\"a\",\"Index\":1,\"Time\":1.5,\"Big\":5000000000}}";
		assertEquals(json, RCKEquipmentProperties.parse(MAPPER.readTree(json)).toJsonNode().toString());
	}

	@Test
	public void accessorsFollowMessageOrderAndAreReadOnly() throws Exception {
		JsonNode message = MAPPER.readTree("{\"Press\":[{\"Name\":\"TP\",\"Index\":2,\"Time\":45,\"Clamp\":2},"
											+ "{\"Name\":\"TP\",\"Index\":1,\"Time\":40}],"
											+ "\"Washer\":{\"Name\":\"W\",\"Index\":1,\"Time\":90}}");
		RCKEquipmentProperties props = RCKEquipmentProperties.parse(message);

		RCKEquipment press2 = new RCKEquipment("Press", "TP", 2);
		assertEquals(List.of(press2, new RCKEquipment("Press", "TP", 1), new RCKEquipment("Washer", "W", 1)),
					props.equipments());
		assertEquals(List.of("Time", "Clamp"), List.copyOf(props.propertiesOf(press2).keySet()));
		assertEquals(45, props.propertiesOf(press2).get("Time"));
		assertEquals(props.toJsonNode().toString(), props.toString());

		assertThrows(UnsupportedOperationException.class, () -> props.equipments().clear());
		assertThrows(UnsupportedOperationException.class, () -> props.propertiesOf(press2).clear());
		assertThrows(IllegalArgumentException.class, () -> props.propertiesOf(new RCKEquipment("No", "n", 1)));
	}

	@Test
	public void emptyGroupDisappearsAndSingleElementArrayBecomesObject() throws Exception {
		JsonNode message = MAPPER.readTree("{\"E\":[],\"S\":[{\"Name\":\"s\",\"Index\":1}]}");
		assertEquals("{\"S\":{\"Name\":\"s\",\"Index\":1}}",
					RCKEquipmentProperties.parse(message).toJsonNode().toString());
	}

	@Test
	public void nonObjectMessageIsRejected() throws Exception {
		JsonNode top = MAPPER.readTree("{\"nullField\":null,\"array\":[],\"text\":\"x\",\"number\":1}");
		for ( JsonNode node: new JsonNode[] { null, top.path("noSuchField"), top.get("nullField"), top.get("array"),
												top.get("text"), top.get("number") } ) {
			assertThrows("" + node, JacksonDeserializationException.class, () -> RCKEquipmentProperties.parse(node));
		}
	}

	@Test
	public void emptyObjectMeansNoEquipments() throws Exception {
		assertEquals("{}", RCKEquipmentProperties.parse(MAPPER.readTree("{}")).toJsonNode().toString());
	}

	@Test
	public void malformedEquipmentsAreRejected() throws Exception {
		for ( String json: new String[] {
				"{\"A\":{\"Index\":1}}",						// Name 없음
				"{\"A\":{\"Name\":\"a\"}}",						// Index 없음
				"{\"A\":{\"Name\":1,\"Index\":1}}",				// Name이 문자열이 아님
				"{\"A\":{\"Name\":\"a\",\"Index\":\"1\"}}",		// Index가 문자열
				"{\"A\":{\"Name\":\"a\",\"Index\":1.5}}",		// Index가 소수
				"{\"A\":[1]}",									// 설비 항목이 객체가 아님
				"{\"A\":{\"Name\":\"a\",\"Index\":1,\"Time\":\"45\"}}",	// 속성이 문자열
				"{\"A\":{\"Name\":\"a\",\"Index\":1,\"Time\":null}}",		// 속성이 null
				"{\"A\":{\"Name\":\"a\",\"Index\":1,\"Time\":{\"v\":1}}}",	// 속성이 중첩 객체
				"{\"A\":[{\"Name\":\"a\",\"Index\":1,\"Time\":1},{\"Name\":\"a\",\"Index\":1,\"Time\":2}]}"	// 같은 설비 중복
		} ) {
			JsonNode node = MAPPER.readTree(json);
			assertThrows(json, JacksonDeserializationException.class, () -> RCKEquipmentProperties.parse(node));
		}
	}
}
