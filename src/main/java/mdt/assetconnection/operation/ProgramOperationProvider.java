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

import de.fraunhofer.iosb.ilt.faaast.service.ServiceContext;
import de.fraunhofer.iosb.ilt.faaast.service.filestorage.FileStorage;
import de.fraunhofer.iosb.ilt.faaast.service.model.IdShortPath;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.PersistenceException;
import de.fraunhofer.iosb.ilt.faaast.service.model.exception.ResourceNotFoundException;

import utils.InternalException;
import utils.Tuple;
import utils.async.command.CommandExecution;
import utils.async.command.CommandExecution.Output;
import utils.async.command.CommandVariable;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.stream.FStream;

import mdt.config.MDTService;
import mdt.model.MDTModelSerDe;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.task.TaskException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
class ProgramOperationProvider implements OperationProvider {
	private static final Logger s_logger = LoggerFactory.getLogger(ProgramOperationProvider.class);
	
	private final MDTService m_svcContext;
	private final ProgramOperationProviderConfig m_config;
	private final File m_opDescFile;
	
	private CommandExecution m_cmdExec;
	
	ProgramOperationProvider(ServiceContext serviceContext, Reference operationRef,
								ProgramOperationProviderConfig config) throws IOException {
		m_svcContext = (MDTService)serviceContext;
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
		
		var outVarList = FStream.of(inoutputVars)
								.concatWith(FStream.of(outputVars))
								.toList();
		
		File workingDir = opDesc.getWorkingDirectory();
		File envFile = new File(workingDir, "env.file");
		CommandExecution.Builder builder = CommandExecution.builder()
															.addCommand(opDesc.getCommandLine())
															.workingDirectory(workingDir)
															.environmentFile(envFile)
															.timeout(opDesc.getTimeout());
		
		FStream.of(inputVars)
				.concatWith(FStream.of(inoutputVars))
				.mapOrThrow(opv -> newInputCommandVariable(workingDir, opv))
				.forEach(builder::addVariable);
		FStream.of(outputVars)
				.mapOrThrow(opv -> newOutputOnlyCommandVariable(workingDir, opv))
				.forEach(builder::addVariable);

		// stdout/stderr redirection
		builder.stderr(Output.toStdout());
		builder.stdout(Output.file(new File(workingDir, "output.log")));
		
		m_cmdExec = builder.build();
		try {
			m_cmdExec.run();
			s_logger.info("ProgramOperationProvider terminates");
			
			FStream.from(outVarList)
					.tagKey(opv -> opv.getValue().getIdShort())
					.match(m_cmdExec.getVariableMap())
					.forEachOrThrow(match -> {
						Tuple<OperationVariable,CommandVariable> t = match.value();
						handleOutputVariable(t._1, t._2);
					});
		}
		finally {
			m_cmdExec.close();
		}
	}
	
	private CommandVariable newInputCommandVariable(File workingDir, OperationVariable opv) throws TaskException {
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
					
					return new CommandVariable(name, cvFile);
				}
				catch ( ResourceNotFoundException | PersistenceException e ) {
					throw new TaskException("Failed to read AASFile " + aasFile, e);
				}
			}
			else {
				ElementValue smev = ElementValues.getValue(data);
				cvFile = new File(workingDir, name);
				if ( smev == null ) {
					// 빈 파일을 생성함.
					if ( !cvFile.createNewFile() ) {
						String msg = String.format("Failed to create empty file: name=%s, path=%s",
													name, cvFile.getAbsolutePath());
						throw new InternalException(msg);
					}
				}
				else {
					String valStr = smev.toValueJsonString();
					IOUtils.toFile(valStr, StandardCharsets.UTF_8, cvFile);
					return new CommandVariable(name, valStr, cvFile);
				}
					
				return new CommandVariable(name, cvFile);
			}
		}
		catch ( IOException e ) {
			throw new InternalException("Failed to write value to file: name=" + name
										+ ", path=" + cvFile.getAbsolutePath(), e);
		}
	}
	
	private CommandVariable newOutputOnlyCommandVariable(File workingDir, OperationVariable opv) {
		String name = opv.getValue().getIdShort();
		File cvFile = new File(workingDir, name);
		return new CommandVariable(name, cvFile);
	}
	
	private void handleOutputVariable(OperationVariable opv, CommandVariable var)
		throws IOException, PersistenceException {
		SubmodelElement old = opv.getValue();
		if ( old instanceof org.eclipse.digitaltwin.aas4j.v3.model.File aasFile ) {
			String outFilePath = var.getValue();
			if ( outFilePath == null || outFilePath.length() == 0 ) {
				s_logger.warn("Output CommandVariable[{}] has empty value", var.getName());
				return;
			}

			File cvFile = new File(outFilePath);
			try {
				// 출력 파일을 읽어서 AASFile에 upload하도록 한다.
				String aasFileName = cvFile.getName();
				byte[] content = IOUtils.toBytes(cvFile);
				aasFile.setValue(aasFileName);
				
				@SuppressWarnings("rawtypes")
				FileStorage fileStore = m_svcContext.getFileStorage();
				fileStore.save(aasFileName, content);
			}
			finally {
				// 출력 파일은 삭제한다.
				if ( cvFile.isFile() ) {
					if ( !cvFile.delete() ) {
						s_logger.warn("Failed to delete output file: {}", cvFile.getAbsolutePath());
					}
				}
			}
		}
		else {
			// AASFile이 아닌 경우, CommandVariable에서 생성한 valueJson을 SubmodelElement에 설정한다.
			ElementValues.updateWithValueJsonString(old, var.getValue());
		}
	}
}
