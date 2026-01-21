package mdt.ext.rck;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import utils.KeyValue;
import utils.func.Funcs;
import utils.json.JacksonUtils;
import utils.stream.FStream;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RCKSimulationResult {
	private static final Set<String> EQUIP_TYPES
			= Set.of("TP-75", "파츠피더", "가열로", "세척기", "템퍼링호");
	
	private final int m_progress;
	private final int m_production;
	private final Map<String,Float> m_utilizations;
	private JsonNode m_equipmentProperties;
	
	private RCKSimulationResult(int progress, int production, Map<String,Float> utilization) {
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
	
	public Map<String,Float> getUtilizations() {
		return m_utilizations;
	}
	
	@Override
	public String toString() {
		return String.format("progress(%d), productions(%d), utilizations: %s",
							m_progress, m_production, m_utilizations);
	}
	
	public static RCKSimulationResult empty() {
		return new RCKSimulationResult(0, 0, Map.of());
	}
	
	public static RCKSimulationResult parse(JsonNode progressReport) {
		int progress = progressReport.get("Progress").asInt();
		int production = progressReport.path("Conveyor_6").path("Production").asInt();
		
		List<JsonNode> equipList = new LeafObjectNodeCollector(progressReport).collect();
		Map<String, Float> utilsMap
				= FStream.from(equipList)
						.filter(equip -> equip.has("Utilization"))
						.mapToKeyValue(equip -> {
							String name = equip.get("Name").asText();
							int util = equip.get("Utilization").asInt();
							return KeyValue.of(name, util);
						})
						.mapKey(k -> Funcs.findFirst(EQUIP_TYPES, t -> k.startsWith(t)).orElse(""))
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
		
		return new RCKSimulationResult(progress, production, utilsMap);
	}
	
	public static final void main(String[] args) throws Exception {
		JsonMapper mapper = JacksonUtils.newJsonMapper(true);
		JsonNode root = mapper.readTree(new File("/home/kwlee/tmp/simulation_sample.json"));
		
		RCKSimulationResult updater = RCKSimulationResult.parse(root);
		System.out.println(updater);
	}
}
