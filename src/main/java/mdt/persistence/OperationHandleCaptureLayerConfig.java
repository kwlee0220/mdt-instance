package mdt.persistence;

import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;

/**
 * {@link OperationHandleCaptureLayer}의 설정. 별도 설정 값 없이 base persistence 설정만 갖는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationHandleCaptureLayerConfig extends PersistenceStackConfig<OperationHandleCaptureLayer> {
	public OperationHandleCaptureLayerConfig(PersistenceConfig<?> baseConfig) {
		super(baseConfig);
	}

	@Override
	public String toString() {
		return String.format("%s: %s", getClass().getSimpleName(), super.toString());
	}

	@Override
	public boolean equals(Object obj) {
		if ( this == obj ) {
			return true;
		}
		if ( obj == null || !(obj instanceof OperationHandleCaptureLayerConfig) ) {
			return false;
		}

		return super.equals(obj);
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}
}
