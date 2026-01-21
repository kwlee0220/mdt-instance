package mdt.ext.rck;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.Lists;

import utils.KeyValue;
import utils.func.Funcs;
import utils.json.JacksonUtils;
import utils.stream.FStream;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class LeafObjectNodeCollector {
	private final JsonNode m_root;
	
	public LeafObjectNodeCollector(JsonNode root) {
		m_root = root;
	}
	
	public List<JsonNode> collect() {
		List<JsonNode> leafObjNodes = Lists.newArrayList();
		collect(m_root, leafObjNodes);
		
		return leafObjNodes;
	}
	
	private void collect(JsonNode node, List<JsonNode> collected) {
		if ( node.isContainerNode() ) {
			int objChildCount = 0;
			for ( JsonNode child: node ) {
				if ( child.isContainerNode() ) {
					++objChildCount;
					collect(child, collected);
				}
			}
			if ( node.isObject() && objChildCount == 0 ) {
				collected.add(node);
			}
		}
	}
	
	public static final void main(String[] args) throws Exception {
		JsonMapper mapper = JacksonUtils.newJsonMapper(true);
		JsonNode root = mapper.readTree(new File("/home/kwlee/tmp/simulation_sample.json"));
		
		List<String> equipTypes = List.of("TP-75", "세척기", "가열로", "파츠피더", "템퍼링호", "컨베이어",
											"05 CONVERYOR(적재 배출)", "05 CONVERYOR(적재 투입)",
											"소재이송컨베이어", "MD650");
		List<JsonNode> equipList = new LeafObjectNodeCollector(root).collect();
		
		Map<String, Double> utilsMap =
				FStream.from(equipList)
				.mapToKeyValue(equip -> KeyValue.of(equip.get("Name").asText(),
													equip.get("Utilization").asInt()))
				.mapKey(k -> Funcs.findFirst(equipTypes, t -> k.startsWith(t)).orElse(""))
				.groupByKey()
				.fstream()
				.mapValue(values -> values.stream().mapToInt(Integer::intValue).average().orElse(0.0))
				.toMap();
		System.out.println(utilsMap);
		
//		for ( JsonNode jnode : new LeafObjectNodeCollector(root).collect() ) {
//			System.out.println(jnode.toPrettyString());
//		}
	}
}
