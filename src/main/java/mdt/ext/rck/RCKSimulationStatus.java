package mdt.ext.rck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

import utils.KeyValue;
import utils.Preconditions;
import utils.func.Funcs;
import utils.stream.FStream;

/**
 * 시뮬레이터의 {@code simulation_status} 메시지 한 건이 알려 준 진행 상황.
 *
 * @param progress				진척도(%)
 * @param production			생산량
 * @param averageUtilizations	설비 유형별 평균 이용률. 유형 이름 순으로 정렬된 변경 불가 맵.
 * @author Kang-Woo Lee (ETRI)
 */
public record RCKSimulationStatus(int progress, int production, Map<String,Float> averageUtilizations) {
	private static final Set<String> EQUIP_TYPES
			= Set.of("TP-75", "파츠피더", "가열로", "세척기", "템퍼링호");
	private static final RCKSimulationStatus EMPTY = new RCKSimulationStatus(0, 0, Map.of());

	public RCKSimulationStatus {
		Preconditions.checkNotNullArgument(averageUtilizations, "averageUtilizations is null");
		averageUtilizations = Collections.unmodifiableMap(new TreeMap<>(averageUtilizations));
	}

	/**
	 * 아직 진행 보고를 받지 않은 상태(진척도·생산량 0, 이용률 없음)를 반환한다.
	 *
	 * @return	빈 진행 상황
	 */
	public static RCKSimulationStatus empty() {
		return EMPTY;
	}

	@Override
	public String toString() {
		return String.format("progress(%d), productions(%d), utilizations: %s",
							progress, production, averageUtilizations);
	}

	/**
	 * 시뮬레이터가 보낸 {@code simulation_status} 메시지의 본문을 파싱하여 진행 상황을 생성한다.
	 * <p>
	 * 필드 부재는 다음과 같이 서로 다르게 다룬다.
	 * <ul>
	 *   <li>{@code Progress} - 상태 보고 메시지라면 반드시 있어야 하는 필드이므로, 없으면 메시지 형식이
	 *       잘못된 것으로 보고 예외를 발생시킨다.</li>
	 *   <li>{@code Conveyor_6.Production} - 레이아웃에 따라 없을 수 있으므로 0으로 간주한다.</li>
	 *   <li>설비의 {@code Name} / {@code Utilization} - 둘 중 하나라도 없는 설비는 이용률 집계에서
	 *       제외한다.</li>
	 * </ul>
	 * 설비 이용률은 실수로 읽어 설비 유형별로 평균을 내고 소수 셋째 자리까지 반올림한다.
	 *
	 * @param progressReport	{@code simulation_status} 메시지의 {@code message} 부분. ({@code null} 불가)
	 * @return	파싱된 진행 상황.
	 * @throws IllegalArgumentException	{@code progressReport}가 {@code null}이거나 {@code Progress}
	 * 									필드를 갖지 않는 경우.
	 */
	public static RCKSimulationStatus parse(JsonNode progressReport) {
		Preconditions.checkNotNullArgument(progressReport, "progressReport is null");

		JsonNode progressNode = progressReport.get("Progress");
		if ( progressNode == null ) {
			throw new IllegalArgumentException("invalid simulation status message "
												+ "(no 'Progress' field): " + progressReport);
		}
		int progress = progressNode.asInt();
		int production = progressReport.path("Conveyor_6").path("Production").asInt();

		Map<String, Float> utilsMap
				= FStream.from(collectLeafObjects(progressReport))
						.filter(equip -> equip.has("Name") && equip.has("Utilization"))
						.mapToKeyValue(equip -> KeyValue.of(equip.get("Name").asText(),
															equip.get("Utilization").asDouble()))
						.mapKey(k -> {
							var type = Funcs.findFirst(EQUIP_TYPES, t -> k.startsWith(t));
							return type != null ? type : "";
						})
						.filterKey(k -> !k.isEmpty())
						.groupByKey()
						.fstream()
						.mapValue(values -> {
							double avgUtil = values.stream()
													.mapToDouble(Double::doubleValue)
													.average()
													.orElse(0.0);
							return (float)(Math.round(avgUtil * 1000) / 1000.0);
						})
						.toMap();

		return new RCKSimulationStatus(progress, production, utilsMap);
	}

	/**
	 * 주어진 JSON 트리에서 리프 객체 노드(하위에 객체나 배열을 갖지 않는 객체)를 모두 모은다. 시뮬레이터의
	 * 상태 보고에서 설비 하나가 리프 객체 하나에 해당한다.
	 */
	private static List<JsonNode> collectLeafObjects(JsonNode root) {
		List<JsonNode> leaves = new ArrayList<>();
		collectLeafObjects(root, leaves);
		return leaves;
	}

	private static void collectLeafObjects(JsonNode node, List<JsonNode> collected) {
		if ( node.isContainerNode() ) {
			int containerChildCount = 0;
			for ( JsonNode child: node ) {
				if ( child.isContainerNode() ) {
					++containerChildCount;
					collectLeafObjects(child, collected);
				}
			}
			if ( node.isObject() && containerChildCount == 0 ) {
				collected.add(node);
			}
		}
	}
}
