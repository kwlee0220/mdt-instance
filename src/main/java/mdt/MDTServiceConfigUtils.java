package mdt;
import de.fraunhofer.iosb.ilt.faaast.service.config.CoreConfig;
import de.fraunhofer.iosb.ilt.faaast.service.config.ServiceConfig;
import de.fraunhofer.iosb.ilt.faaast.service.endpoint.http.HttpEndpointConfig;
import de.fraunhofer.iosb.ilt.faaast.service.filestorage.filesystem.FileStorageFilesystemConfig;
import de.fraunhofer.iosb.ilt.faaast.service.messagebus.internal.MessageBusInternalConfig;
import de.fraunhofer.iosb.ilt.faaast.service.persistence.memory.PersistenceInMemoryConfig;
import lombok.experimental.UtilityClass;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@UtilityClass
public class MDTServiceConfigUtils {
    public static ServiceConfig getDefaultServiceConfig() {
    	// Note: overriden by kwlee
    	FileStorageFilesystemConfig fsConfig = new FileStorageFilesystemConfig();
    	fsConfig.setPath("./files");
    	fsConfig.setExistingDataPath("./system_files");
        
        return new ServiceConfig.Builder()
                .core(new CoreConfig.Builder().requestHandlerThreadPoolSize(2).build())
                .persistence(new PersistenceInMemoryConfig())
                .fileStorage(fsConfig)
                .endpoint(new HttpEndpointConfig())
                .messageBus(new MessageBusInternalConfig())
                .build();
    }
}
