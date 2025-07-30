package mdt.assetconnection.operation;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.InternalException;
import utils.async.CommandExecution;
import utils.async.CommandVariable;
import utils.async.CommandVariable.FileVariable;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.stream.FStream;
import utils.stream.KeyValueFStream;

import mdt.model.MDTModelSerDe;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.task.TaskException;
import mdt.task.builtin.ProgramOperationDescriptor;

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.filestorage.FileStorage;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
class ProgramOperationProvider implements OperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(ProgramOperationProvider.class);
	
	private final ServiceContext m_svcContext;
	private final ProgramOperationProviderConfig m_config;
	private final File m_opDescFile;
	
	private CommandExecution m_cmdExec;
	
	ProgramOperationProvider(ServiceContext serviceContext, Reference operationRef,
								ProgramOperationProviderConfig config) throws IOException {
		m_svcContext = serviceContext;
		m_config = config;
		
		m_opDescFile = FileUtils.path(FileUtils.getCurrentWorkingDirectory(), m_config.getOperationDescriptorFile());
		if ( m_opDescFile.isFile() && m_opDescFile.canRead() ) {
			if ( s_logger.isInfoEnabled() ) {
				IdShortPath idShortPath = IdShortPath.fromReference(operationRef);
				s_logger.info("Operation: Program ({}), op-ref={}", m_opDescFile.getPath(), idShortPath);
			}
		}
		else {
			throw new FileNotFoundException("Cannot read ProgramOperationDescriptor: path="
											+ m_opDescFile.getAbsolutePath());
		}
	}
	
	@Override
	public void invokeSync(OperationVariable[] inputVars, OperationVariable[] inoutputVars,
							OperationVariable[] outputVars) throws Exception {
		ProgramOperationDescriptor opDesc = ProgramOperationDescriptor.load(m_opDescFile,
																			MDTModelSerDe.getJsonMapper());
		if ( opDesc.getWorkingDirectory() == null ) {
			// 작업 디렉토리가 지정되지 않은 경우는 연산 기술자 파일이 속한 디렉토리로 설정한다.
			opDesc.setWorkingDirectory(m_opDescFile.getParentFile());
		}
		
		File workingDir = opDesc.getWorkingDirectory();
		CommandExecution.Builder builder = CommandExecution.builder()
															.addCommand(opDesc.getCommandLine())
															.setWorkingDirectory(workingDir)
															.setTimeout(opDesc.getTimeout());
		
		FStream.of(inputVars)
				.concatWith(FStream.of(inoutputVars))
				.concatWith(FStream.of(outputVars))
				.mapOrThrow(opv -> newCommandVariable(workingDir, opv))
				.forEach(builder::addVariable);

		// stdout/stderr redirection
		builder.redirectErrorStream();
		builder.redirectStdoutToFile(new File(workingDir, "output.log"));
		
		m_cmdExec = builder.build();
		try {
			m_cmdExec.run();
			if ( s_logger.isInfoEnabled() ) {
				s_logger.info("ProgramOperationProvider terminates");
			}

			FStream.of(inoutputVars)
					.concatWith(FStream.of(outputVars))
					.tagKey(v -> v.getValue().getIdShort())
					.innerJoin(KeyValueFStream.from(m_cmdExec.getVariableMap()))
					.forEachOrThrow(match -> {
						OperationVariable opv = match.value()._1;
						CommandVariable var = match.value()._2;
						
						// CommandExecution에서 생성한 값은 ElementValue의 valueJson형태로 설정됨.
						try {
							SubmodelElement old = opv.getValue();
							ElementValue newVal = ElementValues.parseValueJsonString(old, var.getValue());
							ElementValues.update(old, newVal);
						}
						catch ( Exception e ) {
							s_logger.error("Failed to update OperationVariable: {}, cause={}",
											opv.getValue().getIdShort(), ""+e);
						}
					});
		}
		finally {
			m_cmdExec.close();
		}
	}
	
	private FileVariable newCommandVariable(File workingDir, OperationVariable opv) throws TaskException {
		SubmodelElement data = opv.getValue();
		String name = data.getIdShort();
		
		File cvFile = null;
		try {
			if ( data instanceof org.eclipse.digitaltwin.aas4j.v3.model.File aasFile ) {
				String path = aasFile.getValue();
				if ( path == null || path.length() == 0 ) {
					String details = String.format("AASFile has empty path: OperationVariable[%s]", name);
					throw new TaskException(details);
				}
				cvFile = new File(workingDir, path);
				
				try {
					// AASFile을 통해 파일 content를 읽어서 File CommandVariable 객체를 생성한다.
					@SuppressWarnings("rawtypes")
					FileStorage fileStore = m_svcContext.getFileStorage();
					byte[] content = fileStore.get(path);
					IOUtils.toFile(content, cvFile);
					
					return new FileVariable(name, cvFile);
				}
				catch ( ResourceNotFoundException e ) {
					throw new TaskException("Failed to read AASFile " + aasFile, e);
				}
			}
			else {
				ElementValue smev = ElementValues.getValue(data);
				if ( smev == null ) {
					String msg = String.format("OperationVariable '%s' has no value" , name);
					throw new TaskException(msg);
				}
				
				String valStr = smev.toValueJsonString();
				cvFile = new File(workingDir, name);
				IOUtils.toFile(valStr, StandardCharsets.UTF_8, cvFile);
				
				return new FileVariable(name, cvFile);
			}
		}
		catch ( IOException e ) {
			throw new InternalException("Failed to write value to file: name=" + name
										+ ", path=" + cvFile.getAbsolutePath(), e);
		}
	}
}
