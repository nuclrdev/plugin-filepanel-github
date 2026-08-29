package dev.nuclr.plugin.core.panel.github.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GitHubBranchesTest {

	@Test
	void readsOneBranchPerLineAcrossLineEndings() {
		assertEquals(
				List.of("main", "release/1.x", "feature/spaces in name"),
				GitHubBranches.parseBranchNames("main\r\nrelease/1.x\nfeature/spaces in name\n"));
	}

	@Test
	void skipsBlankLinesAndTrimsPadding() {
		assertEquals(
				List.of("main", "develop"),
				GitHubBranches.parseBranchNames("\n  main  \n\n\tdevelop\t\n   \n"));
	}

	@Test
	void emptyOutputProducesNoBranches() {
		assertTrue(GitHubBranches.parseBranchNames("").isEmpty());
		assertTrue(GitHubBranches.parseBranchNames("   \n\n").isEmpty());
		assertTrue(GitHubBranches.parseBranchNames(null).isEmpty());
	}
}
