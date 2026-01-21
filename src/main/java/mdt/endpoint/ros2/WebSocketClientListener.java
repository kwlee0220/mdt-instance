package mdt.endpoint.ros2;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface WebSocketClientListener {
	/**
	 * WebSocket 서버에 연결되었을 때 호출되는 메소드.
	 * 
	 * @param wsClient WebSocket 클라이언트 인스턴스
	 * @param handshakedata 서버와의 핸드쉐이크 데이터
	 */
	public void onOpen(WebSocketClient wsClient, ServerHandshake handshakedata);
	
	/**
	 * WebSocket 서버로부터 메시지를 수신했을 때 호출되는 메소드.
	 * 
	 * @param wsClient WebSocket 클라이언트 인스턴스
	 * @param message   수신한 메시지
	 */
	public void onMessage(WebSocketClient wsClient, String message) throws Exception;
	
	/**
	 * WebSocket 서버와의 연결이 종료되었을 때 호출되는 메소드.
	 * 
	 * @param wsClient WebSocket 클라이언트 인스턴스
	 * @param code   연결 종료 코드
	 * @param reason 연결 종료 이유
	 * @param remote 원격 서버에 의해 연결이 종료된 경우 {@code true},
	 * 					로컬 클라이언트에 의해 종료된 경우 {@code false}
	 */
	public void onClose(WebSocketClient wsClient, int code, String reason, boolean remote);
	
	/**
	 * WebSocket 서버와의 연결 중 오류가 발생했을 때 호출되는 메소드.
	 * 
	 * @param wsClient WebSocket 클라이언트 인스턴스
	 * @param ex 오류 정보
	 */
	public void onError(WebSocketClient wsClient, Exception ex);
}
