package mdt.ext.rck;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Accessors(prefix="m_")
@JsonIncludeProperties({"fileName", "fileSize", "timestamp"})
public class VideoInfo {
	private String m_fileName;
	private long m_fileSize;
	private String m_timestamp;
}
