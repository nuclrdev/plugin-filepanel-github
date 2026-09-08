package dev.nuclr.plugin.core.panel.github.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GitHubArtifactsTest {

	@Test
	void parsesEverySlurpedPageNewestFirst() throws Exception {
		String json = """
				[
				  {"artifacts":[{
				    "id":11,"name":"linux-build","size_in_bytes":556,"expired":false,
				    "created_at":"2026-08-01T10:15:30Z","updated_at":"2026-08-01T10:16:30Z",
				    "expires_at":"2026-11-01T10:15:30Z",
				    "workflow_run":{"id":2332938,"head_branch":"main"}
				  }]},
				  {"artifacts":[{
				    "id":13,"name":"windows-build","size_in_bytes":453,"expired":true,
				    "created_at":"2026-08-02T10:15:30Z","updated_at":"2026-08-02T10:16:30Z",
				    "expires_at":"2026-08-03T10:15:30Z",
				    "workflow_run":{"id":2332942,"head_branch":"release"}
				  }]}
				]
				""";

		List<GitHubArtifacts.Artifact> artifacts = GitHubArtifacts.parseArtifacts(json);

		assertEquals(List.of(13L, 11L), artifacts.stream().map(GitHubArtifacts.Artifact::getId).toList());
		assertEquals("release", artifacts.getFirst().getBranch());
		assertEquals(2332942, artifacts.getFirst().getRunId());
		assertTrue(artifacts.getFirst().isExpired());
		assertFalse(artifacts.getLast().isExpired());
	}

	@Test
	void ignoresMalformedArtifacts() throws Exception {
		String json = """
				{"artifacts":[null,{"name":"missing-id"},{"id":7},{"id":8,"name":"valid"}]}
				""";

		List<GitHubArtifacts.Artifact> artifacts = GitHubArtifacts.parseArtifacts(json);

		assertEquals(1, artifacts.size());
		assertEquals(8, artifacts.getFirst().getId());
	}

	@Test
	void buildsDocumentedArtifactApiCommands() {
		assertEquals(
				List.of("api", "repos/nuclr/app/actions/artifacts/42/zip"),
				GitHubArtifacts.downloadArguments("nuclr/app", 42));
		assertEquals(
				List.of("api", "--method", "DELETE", "repos/nuclr/app/actions/artifacts/42"),
				GitHubArtifacts.deleteArguments("nuclr/app", 42));
		assertEquals(
				List.of("api", "--paginate", "--slurp", "repos/nuclr/app/actions/artifacts?per_page=100"),
				GitHubArtifacts.listArguments("nuclr/app"));
	}
}
