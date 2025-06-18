package mdt.ksx9101;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.EntityManager;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import mdt.ksx9101.jpa.JpaMDTEntityFactory;
import mdt.model.sm.entity.SubmodelElementEntity;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter
public class EntityConfiguration {
	private final String type;
	private final Object key;
	private final String idShort;
	private final MountPoint mountPoint;
	@JsonIgnore @Getter(lazy=true) private final String rootPath = calcRootPath();
	
	private static final JpaMDTEntityFactory FACTORY = new JpaMDTEntityFactory();
	
	public EntityConfiguration(@JsonProperty("type") String type,
								@JsonProperty("key") Object key,
								@JsonProperty("idShort") String idShort,
								@JsonProperty("mountPoint") MountPoint mountPoint) {
		this.type = type;
		this.key = key;
		this.idShort = idShort;
		this.mountPoint = mountPoint;
	}
	
	public String getIdShort() {
		return (this.idShort != null) ? this.idShort : this.type;
	}
	
	public SubmodelElementEntity loadJpaEntity(EntityManager em) {
		JpaEntityLoader loader = (JpaEntityLoader)FACTORY.newInstance(this.type);
		return (SubmodelElementEntity)loader.load(em, this.key);
	}
	
	@Override
	public String toString() {
		return String.format("Entity: type=%s, key=%s, idShort=%s", getType(), getKey(), getIdShort());
	}
	
	private String calcRootPath() {
		return getMountPoint().idShortPath + "." + getIdShort();
	}
	
	@Getter @EqualsAndHashCode
	public static class MountPoint {
		private final String submodel;
		private final String idShortPath;
		
		@JsonCreator
		public MountPoint(@JsonProperty("submodel") String submodel,
							@JsonProperty("idShortPath") String idShortPath) {
			this.submodel = submodel;
			this.idShortPath = idShortPath;
		}
		
		@Override
		public String toString() {
			return this.submodel + "/" + this.idShortPath;
		}
	}
}
