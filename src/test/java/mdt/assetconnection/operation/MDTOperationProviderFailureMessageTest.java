package mdt.assetconnection.operation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.FileNotFoundException;
import java.util.concurrent.ExecutionException;

import org.junit.Test;


/**
 * {@link MDTOperationProvider#limitMessageLength(Throwable)}의 실패 메시지 길이 제한 테스트.
 */
public class MDTOperationProviderFailureMessageTest {
	private static final int MAX = MDTOperationProvider.MAX_FAILURE_MESSAGE_LENGTH;

	@Test
	public void shortMessageIsKept() {
		Throwable error = new IllegalStateException("x".repeat(MAX));
		assertSame(error, MDTOperationProvider.limitMessageLength(error));
	}

	@Test
	public void nullMessageIsKept() {
		Throwable error = new IllegalStateException();
		assertSame(error, MDTOperationProvider.limitMessageLength(error));
		assertNull(MDTOperationProvider.limitMessageLength(null));
	}

	@Test
	public void longMessageIsTruncated() {
		Throwable error = new FileNotFoundException("a".repeat(20_000_000));
		Throwable limited = MDTOperationProvider.limitMessageLength(error);

		assertTrue(limited instanceof ExecutionException);
		assertSame(error, limited.getCause());
		String suffix = "... (" + (20_000_000 - MAX) + " more chars)";
		assertEquals("a".repeat(MAX) + suffix, limited.getMessage());
	}

	@Test
	public void surrogatePairIsNotSplit() {
		// MAX번째 문자가 surrogate pair의 앞쪽이 되도록 만든다.
		String msg = "a".repeat(MAX - 1) + "😀" + "b".repeat(10);
		Throwable limited = MDTOperationProvider.limitMessageLength(new IllegalStateException(msg));

		assertTrue(limited.getMessage(), limited.getMessage().startsWith("a".repeat(MAX - 1) + "..."));
		assertTrue(limited.getMessage(), limited.getMessage().endsWith("(12 more chars)"));
	}
}
