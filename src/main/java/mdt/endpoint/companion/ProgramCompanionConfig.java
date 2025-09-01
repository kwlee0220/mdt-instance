package mdt.endpoint.companion;

import java.io.File;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import mdt.model.NameValue;

import de.fraunhofer.iosb.ilt.faaast.service.endpoint.EndpointConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix = "m_")
@JsonPropertyOrder({ "command", "workingDirectory", "variables", "timeout", "stdin", "stdout", "stderr" })
public class ProgramCompanionConfig extends EndpointConfig<ProgramCompanion> {
	private List<String> m_command = Lists.newArrayList();
	private File m_workingDirectory;
	private List<NameValue> m_arguments = Lists.newArrayList();
	private String m_restartPolicy = "unless-stopped"; // "always", "on-failure", "unless-stopped", or "no"
}
