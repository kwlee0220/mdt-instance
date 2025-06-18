package mdt.persistence.timeseries;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import utils.json.JacksonUtils;
import utils.stream.FStream;

import mdt.model.MDTModelSerDe;
import mdt.persistence.PersistenceStackConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TimeSeriesPersistenceConfig extends PersistenceStackConfig<TimeSeriesPersistence> {
	private List<TimeSeriesSubmodelConfig> m_timeSeriesSubmodelConfigs = List.of();

	public List<TimeSeriesSubmodelConfig> getTimeSeriesSubmodelConfigs() {
		return m_timeSeriesSubmodelConfigs;
	}

	@Override
	protected void serializeFields(JsonGenerator gen) throws IOException {
		gen.writeArrayFieldStart("timeSeriesSubmodels");
		for ( TimeSeriesSubmodelConfig pubConf: m_timeSeriesSubmodelConfigs ) {
			gen.writeObject(pubConf);
		}
		gen.writeEndArray();
	}

	@Override
	protected void deserializeFields(JsonNode jnode) throws IOException {
		JsonNode smArrayNode = JacksonUtils.getNullableField(jnode, "timeSeriesSubmodels");
		if ( smArrayNode == null || !smArrayNode.isArray() ) {
			throw new IOException("Cannot find 'timeSeriesSubmodels' field or it is not an array");
		}

		ObjectMapper mapper = MDTModelSerDe.getJsonMapper();
		m_timeSeriesSubmodelConfigs
							= FStream.from(smArrayNode.elements())
									.mapOrThrow(smNode -> mapper.treeToValue(smNode, TimeSeriesSubmodelConfig.class))
									.toList();
	}
}
