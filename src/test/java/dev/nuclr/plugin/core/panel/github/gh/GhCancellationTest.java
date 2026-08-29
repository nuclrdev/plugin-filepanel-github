package dev.nuclr.plugin.core.panel.github.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Cancellation is an expected outcome rather than a failure, so it must be
 * distinguishable from a genuine gh error by type, not just by message.
 */
class GhCancellationTest {

	@Test
	void cancellationIsReportedAsItsOwnExceptionType() {
		IOException error = assertThrows(IOException.class,
				() -> Gh.run(List.of("command-that-must-not-run"), new AtomicBoolean(true), Duration.ofSeconds(1)));

		assertInstanceOf(GhCancelledException.class, error);
		assertEquals("GitHub CLI command was cancelled", error.getMessage());
	}

	@Test
	void aCancelledCommandStillTravelsAsAnIOException() {
		// Callers declare `throws IOException`; the subclass must not break them.
		assertTrue(IOException.class.isAssignableFrom(GhCancelledException.class));
	}
}
