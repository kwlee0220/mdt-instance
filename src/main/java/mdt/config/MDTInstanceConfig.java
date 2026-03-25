package mdt.config;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.model.AASUtils;
import mdt.persistence.MDTPersistenceStackConfig;
import mdt.persistence.asset.AssetVariableConfig;
import mdt.persistence.timeseries.TimeSeriesSubmodelConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class MDTInstanceConfig {
	private String m_id;					// MDT 인스턴스 아이디
	private Integer m_port;					// MDT 인스턴스 포트
	private String m_instanceEndpoint;		// MDT 인스턴스에 부여된 endpoint
	private String m_mdtUrl;				// MDTManager URL
	
	private File m_globalConfigFile;		// 글로벌 설정 파일 경로명
	private File m_keyStoreFile;			// Key Store 파일 경로명
	private String m_keyStorePassword;		// Key Store 암호
	private String m_keyPassword;			// Key 암호
	
	private String m_heartbeatInterval;		// (External MDTInstance인 경우) MDTInstance에서 MDTManager로
											// 재접속을 시도하는 주기
	private String m_managerCheckInterval;	// (External MDTInstance가 아닌 경우) MDTInstance에서 주기적으로
											// MDTManager 상태 점검 주기
	
	// 본 MDTInstance에서 제공하는 서비스 엔드포인트 설정. mqtt, ros2, companion 등
	private @Nullable ServiceEndpointConfigs m_serviceEndpoints;
	// 본 MDTInstance에 등록된 자산 변수 설정 목록. 예: Jdbc, mqtt, opcua 등
	private List<AssetVariableConfig> m_assetVariables = Lists.newArrayList();
	// 본 MDTInstance에 설정된 영속성 스택 목록
	private List<MDTPersistenceStackConfig> m_persistenceStacks = Lists.newArrayList();
	// 본 MDTInstance에 설정된 시계열 서브모델 목록
	private List<TimeSeriesSubmodelConfig> m_timeSeriesSubmodels = Lists.newArrayList();
	// 본 MDTInstance에서 제공하는 AAS 연산 설정 목록
	private OperationsConfig m_operations;
	
	public String getMdtUrl() {
		return m_mdtUrl;
	}
	
	public void setMdtUrl(String endpoint) {
		m_mdtUrl = endpoint;
	}
	
	public String getSubmodelEndpoint(String submodelId) {
		String smIdEncoded = AASUtils.encodeBase64UrlSafe(submodelId);
		return String.format("%s/submodels/%s", m_instanceEndpoint, smIdEncoded);
	}
	
	public void setKeyStorePassword(String password) {
		m_keyStorePassword = password;
		if ( m_keyPassword == null ) {
			m_keyPassword = password;
		}
	}
	public void setKeyPassword(String password) {
		m_keyPassword = password;
		if ( m_keyStorePassword == null ) {
			m_keyStorePassword = password;
		}
	}
}
