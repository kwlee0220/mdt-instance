package mdt.ext.rck;

/**
 * RCK 시뮬레이터가 결과 영상에 대해 보내는 메시지들.
 * <p>
 * 시뮬레이터가 필드를 추가해도 역직렬화가 실패하지 않도록, {@link RCKSimulation}의 매퍼는 모르는 필드를 무시한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class VideoMessages {
	public static record VideoReady(String type, String fileName, String downloadUrl, long fileSize) { }
	public static record VideoProgress(String type, String fileName, String progress) { }
	public static record VideoInfo(String type, String status, String fileName, long fileSize,
									int totalChunks, int chunkSize, String timestamp, String downloadUrl) { }
}
