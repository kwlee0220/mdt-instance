package mdt.ext.rck;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.databind.JsonNode;

import utils.Preconditions;

/**
 * 시뮬레이터의 {@code simulation_status} 메시지 한 건이 알려 준 진행 상황.
 *
 * @param progress		진척도(%)
 * @param production	생산량
 * @param utilizations	설비별 이용률. 메시지의 순서를 따르는 변경 불가 맵.
 * @author Kang-Woo Lee (ETRI)
 */
public record RCKSimulationProgress(int progress, int production, Map<RCKEquipment,Float> utilizations) {
	private static final RCKSimulationProgress EMPTY = new RCKSimulationProgress(0, 0, Map.of());

	public RCKSimulationProgress {
		Preconditions.checkNotNullArgument(utilizations, "utilizations is null");
		// Map.copyOf는 순서를 유지하지 않는다.
		utilizations = Collections.unmodifiableMap(new LinkedHashMap<>(utilizations));
	}

	/**
	 * 아직 진행 보고를 받지 않은 상태(진척도·생산량 0, 이용률 없음)를 반환한다.
	 *
	 * @return	빈 진행 상황
	 */
	public static RCKSimulationProgress empty() {
		return EMPTY;
	}

	@Override
	public String toString() {
		return String.format("progress(%d), production(%d), utilizations: %s",
							progress, production, utilizations);
	}

	/**
	 * 진행 보고에서 생산량을 읽는 규칙. 레이아웃에 따라 달라지므로 바꿀 수 있게 둔다.
	 *
	 * @param productionPath	생산량 필드의 경로 (예: {@code ["Utilizations", "Conveyor_6", "Production"]}). 비어 있으면 안 된다.
	 */
	public record Format(List<String> productionPath) {
		/** 기본 규칙: 생산량은 {@code Utilizations.Conveyor_6.Production}. */
		public static final Format DEFAULT = new Format(List.of("Utilizations", "Conveyor_6", "Production"));

		public Format {
			Preconditions.checkArgument(productionPath != null && !productionPath.isEmpty(),
										"productionPath is empty");
			productionPath = List.copyOf(productionPath);
		}

		/** 생산량 필드 경로를 {@code '.'}으로 이은 문자열 (예: {@code "Utilizations.Conveyor_6.Production"}). */
		public String productionPathString() {
			return String.join(".", productionPath);
		}
	}

	/**
	 * 시뮬레이터가 보낸 {@code simulation_status} 메시지의 본문을 파싱하여 진행 상황을 생성한다.
	 * <p>
	 * 필드가 없는 경우와 필드는 있지만 값이 잘못된 경우를 구분한다. 값이 잘못된 것을 0으로 바꾸어 정상 데이터처럼
	 * 쓰지 않는다.
	 * <ul>
	 *   <li>{@code Progress} - 반드시 있어야 하고 숫자여야 한다. 없거나 숫자가 아니면(예: {@code null}, 문자열)
	 *       메시지 형식이 잘못된 것으로 보고 예외를 던진다.</li>
	 *   <li>생산량({@link Format#productionPath()}, 기본 {@code Utilizations.Conveyor_6.Production}) - 레이아웃에 따라 없을 수
	 *       있으므로 없으면 0으로 간주한다. 있는데 숫자가 아니면 예외를 던진다.</li>
	 *   <li>{@code Utilizations} - 설비 그룹별 이용률 객체. 그룹의 값은 설비 하나를 나타내는 객체이거나 그 배열이다.
	 *       <pre>{@code
	 *       { "Press": [ {"Name": "TP-75", "Index": 1, "Utilization": 97.7}, .. ],
	 *         "Washer_1": {"Name": "세척기", "Index": 1, "Utilization": 48.5} }
	 *       }</pre>
	 *       없으면 빈 맵으로 간주하고, 있는데 객체가 아니면 예외를 던진다. {@code Name}(문자열), {@code Index}
	 *       ({@code int} 범위의 정수), {@code Utilization}(숫자) 중 하나라도 없거나 형식이 맞지 않는 설비는 제외한다.
	 *       같은 설비가 두 번 나오면 나중 값을 쓴다.</li>
	 *   <li>{@code ProcessName} 등 그 밖의 필드는 읽지 않는다.</li>
	 * </ul>
	 * 규칙은 {@link Format#DEFAULT}를 쓴다.
	 *
	 * @param progressReport	{@code simulation_status} 메시지의 {@code message} 부분. ({@code null} 불가)
	 * @return	파싱된 진행 상황.
	 * @throws IllegalArgumentException	{@code progressReport}가 {@code null}이거나, {@code Progress}가 없거나 숫자가
	 * 									아니거나, 생산량이 숫자가 아니거나, {@code Utilizations}가 객체가 아닌 경우.
	 */
	public static RCKSimulationProgress parse(JsonNode progressReport) {
		return parse(progressReport, Format.DEFAULT);
	}

	/**
	 * 주어진 규칙으로 {@code simulation_status} 메시지의 본문을 파싱한다. 필드 처리는 {@link #parse(JsonNode)}와 같다.
	 *
	 * @param progressReport	{@code simulation_status} 메시지의 {@code message} 부분. ({@code null} 불가)
	 * @param format			생산량을 읽는 규칙. ({@code null} 불가)
	 * @return	파싱된 진행 상황.
	 * @throws IllegalArgumentException	{@link #parse(JsonNode)}와 같다. {@code format}이 {@code null}인 경우도.
	 */
	public static RCKSimulationProgress parse(JsonNode progressReport, Format format) {
		Preconditions.checkNotNullArgument(progressReport, "progressReport is null");
		Preconditions.checkNotNullArgument(format, "format is null");

		JsonNode progressNode = progressReport.get("Progress");
		if ( progressNode == null || !progressNode.isNumber() ) {
			throw new IllegalArgumentException("invalid simulation status message "
												+ "('Progress' is missing or not a number): " + progressReport);
		}
		int progress = progressNode.asInt();

		JsonNode productionNode = progressReport;
		for ( String field: format.productionPath() ) {
			productionNode = productionNode.path(field);
		}
		if ( !productionNode.isMissingNode() && !productionNode.isNumber() ) {
			throw new IllegalArgumentException("invalid simulation status message ('"
												+ format.productionPathString() + "' is not a number): "
												+ progressReport);
		}
		int production = productionNode.asInt();

		JsonNode utilsNode = progressReport.path("Utilizations");
		if ( !utilsNode.isMissingNode() && !utilsNode.isObject() ) {
			throw new IllegalArgumentException("invalid simulation status message "
												+ "('Utilizations' is not an object): " + progressReport);
		}
		
		// 설비별 이용률을 읽는다. 잘못된 원소는 제외한다.
		Map<RCKEquipment,Float> utilizations = new LinkedHashMap<>();
		for ( Entry<String,JsonNode> groupEntry: utilsNode.properties() ) {
			JsonNode groupNode = groupEntry.getValue();
			if ( groupNode.isArray() ) {
				groupNode.forEach(equipmentNode -> putUtilization(groupEntry.getKey(), equipmentNode, utilizations));
			}
			else {
				putUtilization(groupEntry.getKey(), groupNode, utilizations);
			}
		}

		return new RCKSimulationProgress(progress, production, utilizations);
	}

	private static void putUtilization(String group, JsonNode equipmentNode, Map<RCKEquipment,Float> utilizations) {
		JsonNode name = equipmentNode.path("Name");
		JsonNode index = equipmentNode.path("Index");
		JsonNode utilization = equipmentNode.path("Utilization");
		if ( name.isTextual() && index.isIntegralNumber() && index.canConvertToInt() && utilization.isNumber() ) {
			utilizations.put(new RCKEquipment(group, name.textValue(), index.intValue()), utilization.floatValue());
		}
	}
}
