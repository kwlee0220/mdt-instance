package mdt.ext.rck;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import utils.KeyValue;
import utils.Preconditions;
import utils.func.Funcs;
import utils.stream.FStream;

/**
 * 시뮬레이터의 {@code simulation_status} 메시지 한 건이 알려 준 진행 상황(진척도·생산량·설비 유형별 평균 이용률).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationStatus {
	private static final Set<String> EQUIP_TYPES
			= Set.of("TP-75", "파츠피더", "가열로", "세척기", "템퍼링호");
	
	private final int m_progress;
	private final int m_production;
	private final Map<String,Float> m_utilizations;
	
	private RCKSimulationStatus(int progress, int production, Map<String,Float> utilization) {
		m_progress = progress;
		m_production = production;
		m_utilizations = utilization;
	}
	
	public int getProgress() {
		return m_progress;
	}
	
	public int getProduction() {
		return m_production;
	}
	
	public Map<String,Float> getAverageUtilizations() {
		return m_utilizations;
	}
	
	@Override
	public String toString() {
		return String.format("progress(%d), productions(%d), utilizations: %s",
							m_progress, m_production, m_utilizations);
	}
	
	public static RCKSimulationStatus empty() {
		return new RCKSimulationStatus(0, 0, Map.of());
	}
	
	/**
	 * 시뮬레이터가 보낸 {@code simulation_status} 메시지의 본문을 파싱하여 시뮬레이션 결과를 생성한다.
	 * <p>
	 * 필드 부재는 다음과 같이 서로 다르게 다룬다.
	 * <ul>
	 *   <li>{@code Progress} - 상태 보고 메시지라면 반드시 있어야 하는 필드이므로, 없으면 메시지 형식이
	 *       잘못된 것으로 보고 예외를 발생시킨다.</li>
	 *   <li>{@code Conveyor_6.Production} - 레이아웃에 따라 없을 수 있으므로 0으로 간주한다.</li>
	 *   <li>설비의 {@code Name} / {@code Utilization} - 둘 중 하나라도 없는 설비는 이용률 집계에서
	 *       제외한다.</li>
	 * </ul>
	 *
	 * @param progressReport	{@code simulation_status} 메시지의 {@code message} 부분. ({@code null} 불가)
	 * @return	파싱된 시뮬레이션 결과.
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

		List<JsonNode> equipList = new LeafObjectNodeCollector(progressReport).collect();
		Map<String, Float> utilsMap
				= FStream.from(equipList)
						.filter(equip -> equip.has("Name") && equip.has("Utilization"))
						.mapToKeyValue(equip -> {
							String name = equip.get("Name").asText();
							int util = equip.get("Utilization").asInt();
							return KeyValue.of(name, util);
						})
						.mapKey(k -> {
							var type = Funcs.findFirst(EQUIP_TYPES, t -> k.startsWith(t));
							return type != null ? type : "";
						})
						.filterKey(k -> !k.isEmpty())
						.groupByKey()
						.fstream()
						.mapValue(values -> {
							float avgUtil = (float)values.stream()
														.mapToInt(Integer::intValue)
														.average()
														.orElse(0.0);
							return (float)(Math.round(avgUtil * 1000f) / 1000f);
						})
						.toMap();
		
		return new RCKSimulationStatus(progress, production, utilsMap);
	}
}
