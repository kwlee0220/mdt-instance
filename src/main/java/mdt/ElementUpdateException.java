package mdt;

import org.jetbrains.annotations.Nullable;

import de.fraunhofer.iosb.ilt.faaast.service.model.api.StatusCode;


/**
 * FA³ST를 통한 AAS 요소 값 갱신이 실패한 경우 발생하는 예외.
 * <p>
 * FA³ST의 {@code ServiceContext.execute()}는 요청 처리 중 발생한 예외(값 파싱 실패, 요소 없음, 저장 실패 등)를
 * 던지지 않고 오류 상태의 응답으로 바꿔 반환한다. {@link FaaastRuntime}은 응답 상태를 확인하여 실패를 이 예외로
 * 알린다. 예외 메시지에는 응답의 상태 코드와 FA³ST가 남긴 메시지가 포함된다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ElementUpdateException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	@Nullable private final StatusCode m_statusCode;

	public ElementUpdateException(String msg, @Nullable StatusCode statusCode) {
		super(msg);
		m_statusCode = statusCode;
	}

	public ElementUpdateException(String msg, Throwable cause) {
		super(msg, cause);
		m_statusCode = null;
	}

	/**
	 * FA³ST 응답의 상태 코드를 반환한다.
	 *
	 * @return	상태 코드. 요청을 보내기 전에 실패한 경우(예: 타입 정보 조회 실패)에는 {@code null}.
	 */
	public @Nullable StatusCode getStatusCode() {
		return m_statusCode;
	}
}
