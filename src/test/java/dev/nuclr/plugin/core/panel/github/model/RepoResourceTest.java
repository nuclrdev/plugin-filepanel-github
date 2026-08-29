package dev.nuclr.plugin.core.panel.github.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.github.gh.GitHubRepos;

class RepoResourceTest {

	@Test
	void uuidUsesStableRepositoryIdentity() {
		GitHubRepos.Repository repository = new GitHubRepos.Repository();
		repository.setNameWithOwner("nuclrdev/commander");
		repository.setVisibility("public");

		RepoResource resource = new RepoResource(repository);

		assertEquals("gh://repo/nuclrdev/commander", resource.getUuid());
		assertEquals("nuclrdev/commander", resource.getName());
		assertEquals("public", resource.getMetadata(GitHubRepos.Visibility, ""));
	}
}
