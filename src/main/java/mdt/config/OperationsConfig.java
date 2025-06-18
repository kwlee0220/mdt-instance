package mdt.config;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.config.AASOperationConfig.HttpOperationConfig;
import mdt.config.AASOperationConfig.JavaOperationConfig;
import mdt.config.AASOperationConfig.ProgramOperationConfig;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
public class OperationsConfig {
	private List<ProgramOperationConfig> m_programOperations = List.of();
	private List<JavaOperationConfig> m_javaOperations = List.of();
	private List<HttpOperationConfig> m_httpOperations = List.of();
}
