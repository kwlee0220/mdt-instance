package mdt.persistence.timeseries;

import java.time.Duration;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.experimental.Accessors;

import utils.UnitUtils;
import utils.func.Funcs;
import utils.func.Optionals;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TimeSeriesSubmodelConfig {
	private final String m_idShort;
	private final String m_endpoint;
	private final String m_tableName;
	private final List<ParameterColumn> m_parameterColumns;
	private final TailConfig m_tail;
	
	public TimeSeriesSubmodelConfig(@JsonProperty("idShort") String idShort,
		                            @JsonProperty("endpoint") String endpoint,
		                            @JsonProperty("tableName") String tableName,
		                            @JsonProperty("parameterColumns") List<ParameterColumn> paramColumns,
		                            @JsonProperty("tail") TailConfig tailConfig) {
		m_idShort = idShort;
		m_endpoint = endpoint;
		m_tableName = tableName;
		m_parameterColumns = paramColumns;
		m_tail = tailConfig;
	}
	
	/**
	 * 대상 시계열 Submodel의 IDShort을 반환한다.
	 *
	 * @return	대상 시계열 Submodel의 IDShort
	 */
	public String getIdShort() {
		return m_idShort;
	}
	
	/**
	 * 대상 시계열 데이터가 저장된 데이터베이스 시스템의 endpoint를 반환한다.
	 *
	 * @return 시계열 데이터베이스 시스템의 Endpoint
	 */
	public String getEndpoint() {
		return m_endpoint;
	}
	
	/**
	 * 대상 시계열 데이터가 저장된 테이블의 이름을 반환한다.
	 *
	 * @return 시계열 데이터베이스 시스템의 테이블 이름
	 */
	public String getTableName() {
		return m_tableName;
	}
	
	/**
	 * 대상 시계열 데이터가 저장된 테이블에 타임스탬프 컬럼의 이름을 반환한다.
	 * 
	 * @return 타임스탬프 컬럼의 이름
	 */
	public String getTimestampColumn() {
		return Funcs.findFirst(m_parameterColumns, pc -> pc.getParameter().equals("Time"))
					.get().getColumn();
	}
	
	/**
	 * 대상 시계열 데이터의 파라미터 컬럼과 DB 컬럼의 매핑 정보를 반환한다.
	 *
	 * @return	파라미터 컬럼과 DB 컬럼의 매핑 정보
	 */
	public List<ParameterColumn> getParameterColumns() {
		return m_parameterColumns;
	}
	
	/**
	 * 시계열 데이터 최근 영역에 해당하는 세그먼트의 설정을 반환한다.
	 * 
	 * @return Tail 세그먼트 설정 정보.
	 */
	public TailConfig getTail() {
		return m_tail;
	}
	
	@Override
	public String toString() {
		return String.format("TimeSeries[idShort=%s, endpoint=%s, table=%s", m_idShort, m_endpoint, m_tableName);
	}
	
	public static enum Order {
		ASC, DESC;

		public static Order parse(String str) {
			return Order.valueOf(str.toUpperCase());
		}
	}

	@Getter
	@Accessors(prefix="m_")
	@JsonIncludeProperties({"length", "duration"})
	public static class TailConfig {
		private final Integer m_length;
		private final Duration m_duration;
		
		public TailConfig(@Nullable @JsonProperty("length") Integer length,
						@Nullable @JsonProperty("duration") String duration) {
			if ( length == null && duration == null ) {
				length = 100;
			}
			
			m_length = length;
			m_duration = UnitUtils.parseDuration(duration);
		}
		
		public Integer getLength() {
			return m_length;
		}
		
		@JsonProperty("duration")
		public String getDurationForJackson() {
			return Optionals.map(m_duration, Duration::toString);
		}
		
		public Duration getDuration() {
			return m_duration;
		}
		
		@Override
		public String toString() {
			String details = (m_length != null)
							? String.format("length=%d", m_length)
							: String.format("duration=%s", m_duration);
			return String.format("Tail[%s] (%s)", details);
		}
	};

	public static final class ParameterColumn {
		private final String m_parameter;
		private final String m_column;

		public ParameterColumn(@JsonProperty("parameter") String parameter,
								@JsonProperty("column") String column) {
			m_parameter = parameter;
			m_column = column;
		}

		public String getParameter() {
			return m_parameter;
		}

		public String getColumn() {
			return m_column;
		}

		public String toString() {
			return String.format("%s:%s", m_parameter, m_column);
		}
	}
}
