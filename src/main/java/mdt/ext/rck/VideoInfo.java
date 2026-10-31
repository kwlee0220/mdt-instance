package mdt.ext.rck;

/**
 * 시뮬레이터가 결과 영상에 대해 보내는 {@code video_info} 메시지.
 * <p>
 * 결과로는 {@link #downloadUrl()}만 쓰고, {@link #fileSize()}는 이어서 오는 이진 영상 데이터를 다 받았는지 판단하는 데
 * 쓴다. 시뮬레이터가 필드를 추가해도 역직렬화가 실패하지 않도록, {@link RCKSimulation}의 매퍼는 모르는 필드를
 * 무시한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public record VideoInfo(String type, String status, String fileName, long fileSize,
						int totalChunks, int chunkSize, String timestamp, String downloadUrl) { }
