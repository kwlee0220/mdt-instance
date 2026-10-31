package mdt.ext.rck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * {@link RCKSimulationProgress#parse(JsonNode)}의 필드 처리와 이용률 집계를 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationProgressTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode json(String text) throws Exception {
		return MAPPER.readTree(text.replace('\'', '"'));
	}

	@Test
	public void parses_progress_production_and_average_utilization_per_type() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 42, 'Conveyor_6': {'Production': 7},"
			+ " 'line': {'a': {'Name': 'TP-75 #1', 'Utilization': 40},"
			+ "          'b': {'Name': 'TP-75 #2', 'Utilization': 45.5},"
			+ "          'c': {'Name': '가열로 1', 'Utilization': 10}}}"));

		assertEquals(42, status.progress());
		assertEquals(7, status.production());
		// 실수 이용률을 잘라내지 않고 평균한다((40 + 45.5) / 2).
		assertEquals(Map.of("TP-75", 42.75f, "가열로", 10f), status.averageUtilizations());
		// 유형 이름 순으로 정렬된다.
		assertEquals(List.of("TP-75", "가열로"), List.copyOf(status.averageUtilizations().keySet()));
	}

	@Test
	public void missing_production_is_zero_and_incomplete_or_unknown_equipment_is_skipped() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 1,"
			+ " 'a': {'Name': 'TP-75 #1'},"
			+ " 'b': {'Utilization': 50},"
			+ " 'c': {'Name': '컨베이어', 'Utilization': 70}}"));

		assertEquals(0, status.production());
		assertTrue(status.averageUtilizations().isEmpty());
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
							"{'Progress': 10, 'Conveyor_6': {'Production': 'oops'}}")));
	}

	@Test
	public void non_numeric_utilization_is_excluded_from_the_average() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 1,"
			+ " 'a': {'Name': 'TP-75 #1', 'Utilization': 40},"
			+ " 'b': {'Name': 'TP-75 #2', 'Utilization': 'oops'},"
			+ " 'c': {'Name': 'TP-75 #3', 'Utilization': null}}"));

		assertEquals(Map.of("TP-75", 40f), status.averageUtilizations());
	}

	@Test
	public void utilization_map_is_unmodifiable() throws Exception {
		RCKSimulationProgress status = RCKSimulationProgress.parse(json(
			"{'Progress': 1, 'a': {'Name': 'TP-75', 'Utilization': 1}}"));

		assertThrows(UnsupportedOperationException.class, () -> status.averageUtilizations().put("x", 1f));
	}
}
