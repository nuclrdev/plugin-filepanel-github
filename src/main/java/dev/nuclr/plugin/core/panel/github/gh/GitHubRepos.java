package dev.nuclr.plugin.core.panel.github.gh;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.NuclrResourceData;
import dev.nuclr.plugin.core.panel.github.model.RepoResource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
public final class GitHubRepos {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final List<String> LIST_ARGUMENTS = List.of(
			"api",
			"--paginate",
			"--slurp",
			"user/repos?per_page=100&affiliation=owner%2Ccollaborator%2Corganization_member"
					+ "&sort=full_name&direction=asc");

	public static final String RepositoryName = "Repository name";
	public static final String Visibility = "Visibility";

	private GitHubRepos() {
	}

	/** Get all repositories accessible to the authenticated user. */
	public static NuclrResourceData repos() {
		return repos(null);
	}

	/** Get all repositories accessible to the authenticated user. */
	public static NuclrResourceData repos(AtomicBoolean cancelled) {
		var data = new NuclrResourceData();
		data.setColumnNames(List.of(RepositoryName, Visibility));

		for (Repository repo : loadAllRepos(cancelled)) {
			data.getEntries().add(new RepoResource(repo));
		}
		return data;
	}

	/**
	 * Fetch every page from {@code GET /user/repos}. The explicit affiliations
	 * include owned, collaborator, and organization-member repositories.
	 */
	public static List<Repository> loadAllRepos() {
		return loadAllRepos(null);
	}

	public static List<Repository> loadAllRepos(AtomicBoolean cancelled) {
		try {
			return parseRepositories(Gh.run(LIST_ARGUMENTS, cancelled));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("GitHub repository listing was interrupted");
			return List.of();
		} catch (GhCancelledException e) {
			log.debug("GitHub repository listing was cancelled");
			return List.of();
		} catch (IOException e) {
			log.error("Failed to load GitHub repositories: {}", e.getMessage(), e);
			return List.of();
		}
	}

	/** Parse the array-of-pages emitted by {@code gh api --paginate --slurp}. */
	static List<Repository> parseRepositories(String json) throws IOException {
		if (json == null || json.isBlank()) {
			return List.of();
		}

		JsonNode root = MAPPER.readTree(json);
		Map<String, Repository> repositories = new LinkedHashMap<>();
		collectRepositories(root, repositories);

		List<Repository> result = new ArrayList<>(repositories.values());
		result.sort((left, right) -> left.nameWithOwner.compareToIgnoreCase(right.nameWithOwner));
		return List.copyOf(result);
	}

	private static void collectRepositories(JsonNode node, Map<String, Repository> repositories) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isArray()) {
			node.forEach(child -> collectRepositories(child, repositories));
			return;
		}
		if (!node.isObject()) {
			return;
		}

		String name = node.path("full_name").asText("").strip();
		if (name.isBlank()) {
			name = node.path("nameWithOwner").asText("").strip();
		}
		if (name.isBlank()) {
			return;
		}

		Repository repository = new Repository();
		repository.setNameWithOwner(name);
		repository.setVisibility(node.path("visibility").asText(""));
		repositories.putIfAbsent(name, repository);
	}

	@Data
	public static class Repository {
		private String nameWithOwner;
		private String visibility;

		public String getDisplayName() {
			return nameWithOwner;
		}
	}
}
