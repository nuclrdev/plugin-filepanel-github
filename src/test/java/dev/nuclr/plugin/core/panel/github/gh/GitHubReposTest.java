package dev.nuclr.plugin.core.panel.github.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GitHubReposTest {

	@Test
	void parsesAndFlattensEveryPaginatedApiPage() throws Exception {
		String json = """
				[
				  [
				    {"full_name":"zeta/owned","visibility":"private"},
				    {"full_name":"Acme/team-repo","visibility":"internal"}
				  ],
				  [
				    {"full_name":"friend/collaboration","visibility":"public"}
				  ]
				]
				""";

		List<GitHubRepos.Repository> repositories = GitHubRepos.parseRepositories(json);

		assertEquals(
				List.of("Acme/team-repo", "friend/collaboration", "zeta/owned"),
				repositories.stream().map(GitHubRepos.Repository::getNameWithOwner).toList());
		assertEquals("internal", repositories.getFirst().getVisibility());
	}

	@Test
	void ignoresMalformedEntriesAndDeduplicatesRepositories() throws Exception {
		String json = """
				[[
				  null,
				  {"visibility":"private"},
				  {"full_name":"owner/repo","visibility":"private"},
				  {"full_name":"owner/repo","visibility":"public"}
				]]
				""";

		List<GitHubRepos.Repository> repositories = GitHubRepos.parseRepositories(json);

		assertEquals(1, repositories.size());
		assertEquals("owner/repo", repositories.getFirst().getNameWithOwner());
		assertEquals("private", repositories.getFirst().getVisibility());
	}

	@Test
	void blankResponseProducesAnEmptyList() throws Exception {
		assertTrue(GitHubRepos.parseRepositories("  ").isEmpty());
	}
}
