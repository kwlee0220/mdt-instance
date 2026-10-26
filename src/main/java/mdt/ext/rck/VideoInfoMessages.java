package mdt.ext.rck;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class VideoInfoMessages {
	public static record VideoReady(String type, String fileName, String downloadUrl, long fileSize) { }
	public static record VideoProgress(String type, String fileName, String progress) { }
	public static record VideoInfo(String type, String status, String fileName, long fileSize,
									int totalChunks, int chunkSize, String timestamp, String downloadUrl) { }
}
