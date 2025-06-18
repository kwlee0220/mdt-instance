package mdt.ksx9101;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Lists;

import utils.stream.FStream;

import de.fraunhofer.iosb.ilt.faaast.service.persistence.PersistenceConfig;
import lombok.Getter;
import lombok.Setter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@JsonIgnoreProperties(value = {"entityConfigs"})
public class KSX9101PersistencerConfig extends PersistenceConfig<KSX9101Persistence> {
	@JsonProperty("entities")
	private List<EntityConfiguration> entityConfigs = Lists.newArrayList();
	@JsonProperty("jpa")
	private JpaConfiguration jpaConfig;

    public static Builder builder() {
        return new Builder();
    }
	
	public List<EntityConfiguration> findSubEntityConfigurations(String pathStr) {
		return FStream.from(entityConfigs)
						.filter(conf -> conf.getRootPath().startsWith(pathStr))
						.toList();
	}
	
	public EntityConfiguration findCoverEntityConfiguration(String pathStr) {
		return FStream.from(this.entityConfigs)
						.findFirst(conf -> pathStr.startsWith(conf.getRootPath()))
						.getOrNull();
	}

    private abstract static class AbstractBuilder<T extends KSX9101PersistencerConfig,
    												B extends AbstractBuilder<T, B>>
    	extends PersistenceConfig.AbstractBuilder<KSX9101Persistence, T, B> {
        public B entityConfigs(List<EntityConfiguration> value) {
            getBuildingInstance().setEntityConfigs(value);
            return getSelf();
        }

        public B jpaConfiguration(JpaConfiguration value) {
            getBuildingInstance().setJpaConfig(value);
            return getSelf();
        }
    }

    public static class Builder extends AbstractBuilder<KSX9101PersistencerConfig, Builder> {
        @Override
        protected Builder getSelf() {
            return this;
        }

        @Override
        protected KSX9101PersistencerConfig newBuildingInstance() {
            return new KSX9101PersistencerConfig();
        }
    }
}
