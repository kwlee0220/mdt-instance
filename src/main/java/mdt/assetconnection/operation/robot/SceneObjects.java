package mdt.assetconnection.operation.robot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.digitaltwin.aas4j.v3.model.DataTypeDefXsd;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.model.MDTModelSerDe;
import mdt.model.sm.SubmodelUtils;


/**
 * 로봇 트윈의 scene 물체 정보를 AAS SubmodelElement로 변환한다.
 * <p>
 * 로봇 트윈은 물체 정보를 두 가지 모양으로 준다.
 * <ul>
 *   <li>{@code reset_scene} 출력의 {@code objects} — 명령한 배치의 리스트.
 *       {@code [{name, type, dimensions, position, orientation}, ...]}</li>
 *   <li>{@code scene_objects} 변수의 {@code objects} — 실제 위치의 맵.
 *       {@code {이름: {type, dimensions, pose: {position, orientation}}}}</li>
 * </ul>
 * 둘 다 물체 하나를 다음 구조의 SubmodelElementCollection으로 바꾼다. idShort는 물체 이름이다.
 * <pre>
 * &lt;물체 이름&gt;          SMC
 *   Type               xs:string     box / cylinder / sphere ...
 *   Dimensions         SML(xs:float) shape_msgs/SolidPrimitive 순서. box는 [x, y, z],
 *                                    cylinder는 [높이, 반지름], sphere는 [반지름]
 *   Pose               SMC
 *     Position         SMC           x, y, z (xs:float, m)
 *     Orientation      SMC           x, y, z, w (xs:float, 쿼터니언)
 * </pre>
 * 좌표는 로봇 base({@code panda_link0}) 기준이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
final class SceneObjects {
	// 물체 이름이 idShort가 되므로 그 규칙을 따라야 한다.
	private static final Pattern ID_SHORT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
	private static final JsonNode UNIT_QUATERNION = MDTModelSerDe.MAPPER.createObjectNode()
																		.put("x", 0.0).put("y", 0.0)
																		.put("z", 0.0).put("w", 1.0);

	private SceneObjects() {
		throw new AssertionError("Should not be called: " + getClass());
	}

	/**
	 * {@code reset_scene} 출력의 {@code objects} 리스트를 물체 SMC 목록으로 변환한다.
	 *
	 * @param objects	{@code [{name, type, dimensions, position, orientation}, ...]}.
	 * @return	물체 SMC 목록.
	 * @throws IOException	형식이 맞지 않는 경우.
	 */
	static List<SubmodelElement> fromPlacementList(JsonNode objects) throws IOException {
		if ( objects == null || !objects.isArray() ) {
			throw new IOException("reset_scene 'objects' is not a list: " + objects);
		}

		List<SubmodelElement> elements = new ArrayList<>();
		for ( JsonNode obj: objects ) {
			JsonNode name = obj.get("name");
			if ( name == null || !name.isTextual() ) {
				throw new IOException("scene object has no name: " + obj);
			}
			elements.add(toElement(name.asText(), obj, obj.get("position"), obj.get("orientation")));
		}
		return elements;
	}

	/**
	 * {@code scene_objects} 변수의 {@code objects} 맵을 물체 SMC 목록으로 변환한다.
	 *
	 * @param objects	{@code {이름: {type, dimensions, pose: {position, orientation}}}}.
	 * @return	물체 SMC 목록.
	 * @throws IOException	형식이 맞지 않는 경우.
	 */
	static List<SubmodelElement> fromSceneObjectMap(JsonNode objects) throws IOException {
		if ( objects == null || !objects.isObject() ) {
			throw new IOException("scene_objects 'objects' is not a map: " + objects);
		}

		List<SubmodelElement> elements = new ArrayList<>();
		for ( Map.Entry<String,JsonNode> ent: objects.properties() ) {
			JsonNode pose = ent.getValue().path("pose");
			elements.add(toElement(ent.getKey(), ent.getValue(), pose.get("position"), pose.get("orientation")));
		}
		return elements;
	}

	private static SubmodelElement toElement(String name, JsonNode obj, JsonNode position, JsonNode orientation)
		throws IOException {
		if ( !ID_SHORT.matcher(name).matches() ) {
			throw new IOException("scene object name is not a valid idShort: " + name);
		}
		if ( position == null || !position.isObject() ) {
			throw new IOException("scene object has no position: name=" + name);
		}

		List<Property> dims = new ArrayList<>();
		for ( JsonNode dim: obj.path("dimensions") ) {
			dims.add(floatProperty(null, dim));
		}

		// 레시피에 회전이 없으면 로봇 트윈은 orientation을 생략한다 (단위 쿼터니언).
		JsonNode ori = (orientation != null && orientation.isObject()) ? orientation : UNIT_QUATERNION;
		var pose = SubmodelUtils.newSubmodelElementCollection("Pose", List.of(
			SubmodelUtils.newSubmodelElementCollection("Position", List.of(
				floatProperty("x", position.get("x")),
				floatProperty("y", position.get("y")),
				floatProperty("z", position.get("z")))),
			SubmodelUtils.newSubmodelElementCollection("Orientation", List.of(
				floatProperty("x", ori.get("x")),
				floatProperty("y", ori.get("y")),
				floatProperty("z", ori.get("z")),
				floatProperty("w", ori.get("w"))))));

		return SubmodelUtils.newSubmodelElementCollection(name, List.of(
			SubmodelUtils.newProperty("Type", obj.path("type").asText(""), DataTypeDefXsd.STRING),
			SubmodelUtils.newSubmodelElementList("Dimensions", dims, true, DataTypeDefXsd.FLOAT),
			pose));
	}

	private static Property floatProperty(String idShort, JsonNode value) {
		// 로봇 트윈은 유한하지 않은 실수를 null로 보낸다.
		float v = ( value != null && value.isNumber() ) ? value.floatValue() : Float.NaN;
		return SubmodelUtils.newProperty(idShort, Float.toString(v), DataTypeDefXsd.FLOAT);
	}
}
