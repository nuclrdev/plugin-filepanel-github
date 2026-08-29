package dev.nuclr.plugin.core.panel.github.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class GitHubCloneTest {

	@Test
	void cloneCommandLeavesProtocolSelectionToGitHubCli() {
		Path destination = Path.of("work", "repository").toAbsolutePath();

		assertEquals(
				List.of(
						"gh", "repo", "clone", "owner/repo", destination.toString(),
						"--", "--branch", "feature/with spaces", "--single-branch"),
				GitHubClone.cloneCommand(" owner/repo ", "feature/with spaces", destination));
	}

	@Test
	void derivesCloneDirectoryNameFromRepoOrUrl() {
		assertEquals("repo", GitHubClone.repositoryName(" owner/repo "));
		assertEquals("repo", GitHubClone.repositoryName("https://example.test/owner/repo.git"));
	}
}
