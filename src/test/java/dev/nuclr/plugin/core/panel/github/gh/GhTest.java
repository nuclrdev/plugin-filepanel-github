package dev.nuclr.plugin.core.panel.github.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class GhTest {

	@Test
	void alreadyCancelledCommandDoesNotStart() {
		IOException error = assertThrows(IOException.class,
				() -> Gh.run(List.of("command-that-must-not-run"), new AtomicBoolean(true), Duration.ofSeconds(1)));

		assertEquals("GitHub CLI command was cancelled", error.getMessage());
	}
}
