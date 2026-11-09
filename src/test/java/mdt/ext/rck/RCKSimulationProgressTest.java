package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * {@link RCKSimulationProgress#parse(JsonNode)}의 필드 처리와 설비별 이용률 수집을 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationProgressTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode json(String text) throws Exception {
		return MAPPER.readTree(text.replace('\'', '"'));
	}

	@Test
	public void parses_press_sample_message() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(MAPPER.readTree(new File("misc/utilizations_press.json")));

		assertEquals(5, status.progress());
		assertEquals(2, status.production());
		Map<RCKEquipment,Float> utils = status.utilizations();
		assertEquals(24, utils.size());
		// 배열로 온 그룹과 객체 하나로 온 그룹을 모두 읽는다.
		assertEquals(97.7733466267863f, utils.get(new RCKEquipment("Press", "TP-75", 6)), 0f);
		assertEquals(48.567447162658496f, utils.get(new RCKEquipment("Washer_1", "세척기", 1)), 0f);
		// 정수로 온 값도 읽고, 이름과 번호가 같아도 그룹이 다르면 다른 설비다.
		assertEquals(100f, utils.get(new RCKEquipment("Tempering", "템퍼링호", 1)), 0f);
		assertEquals(8.374875373878366f, utils.get(new RCKEquipment("Conveyor_2", "컨베이어", 1)), 0f);
		assertEquals(10.734463276836157f, utils.get(new RCKEquipment("Conveyor_3", "컨베이어", 1)), 0f);
	}

	@Test
	public void utilizations_follow_message_order() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 5, 'ProcessName': 'x', 'Utilizations': {"
			+ "'B': [{'Name': 'b', 'Index': 2, 'Utilization': 0.25}, {'Name': 'b', 'Index': 1, 'Utilization': 0.5}],"
			+ "'A': {'Name': 'a', 'Index': 1, 'Utilization': 0.0}}}"));

		assertEquals(5, status.progress());
		assertEquals(0, status.production());
		assertEquals(List.of(new RCKEquipment("B", "b", 2), new RCKEquipment("B", "b", 1), new RCKEquipment("A", "a", 1)),
					List.copyOf(status.utilizations().keySet()));
	}

	@Test
	public void repeated_equipment_keeps_last_value() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 1, 'Utilizations': {'A': ["
			+ "{'Name': 'a', 'Index': 1, 'Utilization': 1}, {'Name': 'a', 'Index': 1, 'Utilization': 2}]}}"));

		assertEquals(Map.of(new RCKEquipment("A", "a", 1), 2f), status.utilizations());
	}

	@Test
	public void default_format_does_not_read_top_level_production() throws Exception {
		assertEquals(0, RCKSimulationProgress.parse(json("{'Progress': 5, 'Conveyor_6': {'Production': 7}}")).production());
	}

	@Test
	public void custom_format_reads_another_production_path() throws Exception {
		RCKSimulationProgress.Format format = new RCKSimulationProgress.Format(List.of("Line", "Out", "Count"));
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 5, 'Utilizations': {'Conveyor_6': {'Production': 7}}, 'Line': {'Out': {'Count': 3}}}"), format);

		assertEquals(3, status.production());
		assertThrows(IllegalArgumentException.class, () -> new RCKSimulationProgress.Format(List.of()));
	}

	@Test
	public void incomplete_equipments_are_skipped() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 1, 'Utilizations': {'A': ["
			+ "{'Name': 'x', 'Index': 1, 'Utilization': 40},"	// 정상
			+ "{'Index': 2, 'Utilization': 1},"					// Name 없음
			+ "{'Name': null, 'Index': 3, 'Utilization': 1},"	// Name이 null
			+ "{'Name': 1, 'Index': 4, 'Utilization': 1},"		// Name이 문자열이 아님
			+ "{'Name': 'x', 'Utilization': 1},"				// Index 없음
			+ "{'Name': 'x', 'Index': 'y', 'Utilization': 1},"	// Index가 문자열
			+ "{'Name': 'x', 'Index': 1.5, 'Utilization': 1},"	// Index가 소수
			+ "{'Name': 'x', 'Index': 5000000000, 'Utilization': 1},"	// Index가 int 범위 밖
			+ "{'Name': 'x', 'Index': 6},"						// Utilization 없음
			+ "{'Name': 'x', 'Index': 7, 'Utilization': 'oops'},"	// Utilization이 문자열
			+ "{'Name': 'x', 'Index': 8, 'Utilization': null},"	// Utilization이 null
			+ "1, [{'Name': 'x', 'Index': 9, 'Utilization': 1}]"	// 원소가 객체가 아님
			+ "], 'B': 1, 'C': null}}"));						// 그룹 값이 객체나 배열이 아님

		assertEquals(Map.of(new RCKEquipment("A", "x", 1), 40f), status.utilizations());
	}

	@Test
	public void missing_utilizations_means_empty_and_other_locations_are_not_read() throws Exception {
		assertTrue(RCKSimulationProgress.parse(json(
						"{'Progress': 1, 'a': {'Name': 'TP-75', 'Index': 1, 'Utilization': 1}}")).utilizations().isEmpty());
	}

	@Test
	public void non_object_utilizations_is_rejected() throws Exception {
		for ( String utils: new String[] { "[]", "null", "1", "'x'" } ) {
			assertThrows(utils, IllegalArgumentException.class,
						() -> RCKSimulationProgress.parse(json("{'Progress': 1, 'Utilizations': " + utils + "}")));
		}
	}

	@Test
	public void missing_progress_is_rejected() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> RCKSimulationProgress.parse(json("{'Conveyor_6': {}}")));
	}

	@Test
	public void non_numeric_progress_or_production_is_rejected_not_read_as_zero() throws Exception {
		// 잘못된 값을 0으로 바꾸어 정상 데이터처럼 쓰지 않는다. 호출자(Running)는 이 보고를 경고 후 무시한다.
		assertThrows(IllegalArgumentException.class, () -> RCKSimulationProgress.parse(json("{'Progress': null}")));
		assertThrows(IllegalArgumentException.class, () -> RCKSimulationProgress.parse(json("{'Progress': '10'}")));
		assertThrows(IllegalArgumentException.class, () -> RCKSimulationProgress.parse(json(
							"{'Progress': 10, 'Utilizations': {'Conveyor_6': {'Production': 'oops'}}}")));
	}

	@Test
	public void utilizations_are_unmodifiable_and_copied() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 1, 'Utilizations': {'A': {'Name': 'TP-75', 'Index': 1, 'Utilization': 1}}}"));
		assertThrows(UnsupportedOperationException.class, () -> status.utilizations().clear());

		Map<RCKEquipment,Float> source = new java.util.HashMap<>();
		source.put(new RCKEquipment("A", "a", 1), 1f);
		RCKSimulationProgress copied = new RCKSimulationProgress(1, 0, source);
		source.clear();
		assertEquals(1, copied.utilizations().size());
	}

	@Test
	public void utilization_json_keeps_group_and_message_order() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 1, 'Utilizations': {"
			+ "'B': {'Name': '컨베이어', 'Index': 1, 'Utilization': 0.5},"
			+ "'A': {'Name': '컨베이어', 'Index': 1, 'Utilization': 40}}}"));

		// 문자열로 비교한다(순서까지 확인).
		assertEquals("[{\"group\":\"B\",\"equipment\":\"컨베이어\",\"index\":1,\"utilization\":0.5},"
					+ "{\"group\":\"A\",\"equipment\":\"컨베이어\",\"index\":1,\"utilization\":40.0}]",
					MAPPER.readTree(MDTSimulationOutputReporter.toUtilizationJson(status.utilizations())).toString());
	}
}
