package mdt.assetconnection.operation;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import de.fraunhofer.iosb.ilt.faaast.service.assetconnection.AbstractAssetOperationProviderConfig;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonInclude(Include.NON_NULL)
public class MDTOperationProviderConfig extends AbstractAssetOperationProviderConfig {
	private @Nullable JavaOperationProviderConfig m_java;
	private @Nullable ProgramOperationProviderConfig m_program;
	private @Nullable ScriptOperationProviderConfig m_script;
	private @Nullable HttpOperationProviderConfig m_http;
	
	public MDTOperationProviderConfig() { }
	
	public JavaOperationProviderConfig getJava() {
		return m_java;
	}
	
	public void setJava(JavaOperationProviderConfig java) {
		this.m_java = java;
	}
	
	public ProgramOperationProviderConfig getProgram() {
		return m_program;
	}
	
	public void setProgram(ProgramOperationProviderConfig program) {
		this.m_program = program;
	}
	
	public ScriptOperationProviderConfig getScript() {
		return m_script;
	}
	
	public void setScript(ScriptOperationProviderConfig script) {
		this.m_script = script;
	}
	
	public HttpOperationProviderConfig getHttp() {
		return m_http;
	}
	
	public void setHttp(HttpOperationProviderConfig http) {
		this.m_http = http;
	}
	
	@Override
	public String toString() {
		return "MDTOperationProviderConfig [java=" + m_java + ", program=" + m_program + ", http=" + m_http + "]";
	}
}
