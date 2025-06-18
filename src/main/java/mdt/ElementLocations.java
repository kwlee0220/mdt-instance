package mdt;

import lombok.experimental.UtilityClass;

import mdt.model.sm.ref.MDTArgumentKind;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@UtilityClass
public class ElementLocations {
	public static ElementLocation parseStringExpr(String expr) {
		String[] parts = expr.split(":");
		switch ( parts[0] ) {
			case "param":
				if ( parts.length == 2 ) {
					return new MDTParameterLocation(parts[1], null);
				}
				else {
					return new MDTParameterLocation(parts[1], parts[2]);
				}
			case "oparg":
				return new MDTOperationArgumentLocation(parts[1], MDTArgumentKind.valueOf(parts[2]), parts[3]);
			default:
				if ( parts.length != 2 ) {
					throw new IllegalArgumentException("Invalid ElementLocation expr=" + expr);
				}
				return new DefaultElementLocation(parts[0], parts[1]);
		}
	}
}
