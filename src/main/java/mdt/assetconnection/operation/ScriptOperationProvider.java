package mdt.assetconnection.operation;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;

import utils.KeyValue;
import utils.async.command.CommandExecution;
import utils.func.FOption;
import utils.func.Unchecked;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.json.JacksonUtils;
import utils.stream.FStream;

import mdt.config.MDTService;
import mdt.model.sm.value.ElementValues;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
class ScriptOperationProvider implements OperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(ScriptOperationProvider.class);
	
	@SuppressWarnings("unused")
	private final MDTService m_svcContext;
	private final ScriptOperationProviderConfig m_config;
	private final File m_scriptFile;
	
	private CommandExecution m_cmdExec;
	
	ScriptOperationProvider(ServiceContext serviceContext, Reference operationRef,
								ScriptOperationProviderConfig config) throws IOException {
		m_svcContext = (MDTService)serviceContext;
		m_config = config;
		
		m_scriptFile = FileUtils.path(FileUtils.getCurrentWorkingDirectory(), m_config.getScriptFile());
		if ( m_scriptFile.isFile() && m_scriptFile.canRead() ) {
			if ( s_logger.isInfoEnabled() ) {
				IdShortPath idShortPath = IdShortPath.fromReference(operationRef);
				s_logger.info("Operation: Script ({}), op-ref={}", m_scriptFile.getPath(), idShortPath);
			}
		}
		else {
			throw new FileNotFoundException("Cannot read Script file: path="
											+ m_scriptFile.getAbsolutePath());
		}
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		File workingDir = m_scriptFile.getParentFile();
		File envFile = new File(workingDir, "env.file");
		File scriptDriver = new File(workingDir, "run_script.py");
		File inputsFile = new File(workingDir, "inputs.json");
		File outputsFile = new File(workingDir, "outputs.json");
		
		File scriptFile = m_scriptFile;
		FOption<OperationVariable> oscript = FStream.of(inputVars)
				.findFirst(opv -> opv.getValue().getIdShort().equals("script"));
		if ( oscript.isPresent() ) {
			SubmodelElement scriptSme = oscript.get().getValue();
			if ( scriptSme instanceof Property prop ) {
				String script = prop.getValue();
				if ( script != null && !script.isBlank() ) {
					scriptFile = FileUtils.path(workingDir, "__script.py");
					IOUtils.toFile(script, scriptFile);
				}
			}
			else {
				throw new IllegalArgumentException("Invalid OperationVariable['script'] (not Property): "
													+ scriptSme.getClass().getName());
			}
		}
		if ( !scriptFile.isFile() ) {
			throw new FileNotFoundException("Cannot find script file: path=" + scriptFile.getAbsolutePath());
		}
		
		List<String> cmdLine = List.of("uv", "run", "python", scriptDriver.getAbsolutePath(),
										"--script", scriptFile.getAbsolutePath(),
										"--inputs", inputsFile.getAbsolutePath(),
										"--outputs", outputsFile.getAbsolutePath());
		
		Map<String, JsonNode> inputNodes = 
						FStream.of(inputVars)
								.filter(opv -> !opv.getValue().getIdShort().equals("script"))
								.map(opv -> opv.getValue())
								.mapToKeyValue(sme -> KeyValue.of(sme.getIdShort(),
																ElementValues.getValue(sme).toValueJsonNode()))
								.toMap();
		JacksonUtils.MAPPER.writeValue(inputsFile, inputNodes);
		
		CommandExecution.Builder builder = CommandExecution.builder()
															.addCommand(cmdLine)
															.workingDirectory(workingDir)
															.environmentFile(envFile)
															.timeout(m_config.getTimeout());
		// stdout/stderr redirection
		builder.redirectErrorStream();
		builder.redirectStdoutToFile(new File(workingDir, "output.log"));
		
		m_cmdExec = builder.build();
		try {
			m_cmdExec.run();
			s_logger.info("ScriptOperationProvider terminates");
			
			JsonNode topNode = JacksonUtils.MAPPER.readTree(outputsFile);
			
			FStream.of(outputVars)
					.lookup(opv -> topNode.get(opv.getValue().getIdShort()), false)
					.forEach((opv, jnode) -> {
						try {
							SubmodelElement old = opv.getValue();
							ElementValues.update(old, jnode);
						}
						catch ( Exception e ) {
							s_logger.error("Failed to update OperationVariable: {}, cause={}",
											opv.getValue().getIdShort(), ""+e);
						}
					});
		}
		finally {
			m_cmdExec.close();
			
			Unchecked.runOrIgnore(() -> Files.deleteIfExists(inputsFile.toPath()));
			Unchecked.runOrIgnore(() -> Files.deleteIfExists(outputsFile.toPath()));
			if ( m_scriptFile != scriptFile ) {
				final File scriptFileToDelete = scriptFile;
				Unchecked.runOrIgnore(() -> Files.deleteIfExists(scriptFileToDelete.toPath()));
			}
		}
	}
}
