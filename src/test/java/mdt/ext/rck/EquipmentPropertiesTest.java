package mdt.ext.rck;

import java.io.File;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class EquipmentPropertiesTest {
	private static final JsonMapper MAPPER = JsonMapper.builder()
														.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
														.build();
	
	public static final void main(String... args) throws Exception {
		File msgFile = new File("misc/facility_press.json");
		JsonNode topNode = MAPPER.readTree(msgFile);
		
		JsonNode msg = topNode.path("message");
		RCKEquipmentPropertiesList equipProps = RCKEquipmentPropertiesList.parse(msg);
		
		// equipProps 를 다시 JSON 으로 변환하여 출력
		System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(equipProps.toJsonNode()));
		
		System.out.println(equipProps);
	}
}
