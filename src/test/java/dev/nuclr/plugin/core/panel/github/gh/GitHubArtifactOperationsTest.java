package dev.nuclr.plugin.core.panel.github.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GitHubArtifactOperationsTest {

	@Test
	void sanitizesArtifactNamesForTheLocalFilesystem() {
		assertEquals("windows_build_1.zip", GitHubArtifactOperations.safeFileName("windows:build/1"));
		assertEquals("already.zip", GitHubArtifactOperations.safeFileName("already.zip"));
		assertEquals("artifact.zip", GitHubArtifactOperations.safeFileName("..."));
	}
}
