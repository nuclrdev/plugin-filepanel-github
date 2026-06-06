package dev.nuclr.plugin.core.panel.github.gh;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.NuclrResourceData;
import dev.nuclr.plugin.core.panel.github.model.RepoResource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GitHubRepos {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	public static final String RepositoryName = "Repository name";
	public static final String Visibility = "Visibility";

	/** Get all repositories the user has access to as NuclrResourceData. */
	public static NuclrResourceData repos() {

		var data = new NuclrResourceData();

		data.setColumnNames(List.of(RepositoryName, Visibility));

		for (var repo : loadAllRepos()) {

			log.info("Found repo: {}", repo);

			var r = new RepoResource(repo);

			data.getEntries().add(r);
		}

		return data;
	}

	/** Get all repositories the user has access to. */
	/** Get all repositories the user has access to. */
	public static List<Repository> loadAllRepos() {

		try {
			Process process = new ProcessBuilder("gh", "repo", "list", "--limit", "1000", "--json",
					"nameWithOwner,visibility").redirectErrorStream(true).start();

			String output;
			try (var reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();

				String line;
				while ((line = reader.readLine()) != null) {
					sb.append(line).append('\n');
				}

				output = sb.toString();
			}

			int exit = process.waitFor();
			if (exit != 0) {
				throw new IOException("gh exited with code " + exit + ": " + output);
			}

			if (output.isBlank()) {
				return List.of();
			}

			return objectMapper.readValue(output,
					objectMapper.getTypeFactory().constructCollectionType(List.class, Repository.class));

		} catch (Exception e) {
			log.error("Failed to load GitHub repositories", e);
			return List.of();
		}
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Repository {
		private String nameWithOwner;
		private String visibility;

		public String getDisplayName() {
			return nameWithOwner;
		}
	}

}