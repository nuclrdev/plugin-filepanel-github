/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.github.gh;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.NuclrResourceData;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.model.ActionsResource;
import dev.nuclr.plugin.core.panel.github.model.ArtifactResource;
import dev.nuclr.plugin.core.panel.github.model.RepoResource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Lists and mutates GitHub Actions artifacts through the GitHub REST API. */
@Slf4j
public final class GitHubArtifacts {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final long WATCHDOG_POLL_MILLIS = 100;

	private GitHubArtifacts() {
	}

	public static NuclrResourceData artifacts(NuclrResource actions, AtomicBoolean cancelled) {
		String repo = actions.getMetadata(ActionsResource.Repo, "");
		var data = new NuclrResourceData();
		data.setColumnNames(List.of(
				ArtifactResource.NameColumn,
				ArtifactResource.RunColumn,
				ArtifactResource.BranchColumn,
				ArtifactResource.SizeColumn,
				ArtifactResource.CreatedColumn,
				ArtifactResource.ExpiresColumn,
				ArtifactResource.StatusColumn));
		data.getEntries().add(upToRepository(repo));

		if (repo.isBlank()) {
			return data;
		}
		try {
			String json = Gh.run(listArguments(repo), cancelled);
			for (Artifact artifact : parseArtifacts(json)) {
				data.getEntries().add(new ArtifactResource(repo, artifact));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Artifact listing for {} was interrupted", repo);
		} catch (GhCancelledException e) {
			log.debug("Artifact listing for {} was cancelled", repo);
		} catch (IOException e) {
			log.error("Failed to list Actions artifacts for {}: {}", repo, e.getMessage(), e);
		}
		return data;
	}

	static List<String> listArguments(String repo) {
		return List.of("api", "--paginate", "--slurp",
				"repos/" + repo + "/actions/artifacts?per_page=100");
	}

	public static void download(String repo, long artifactId, Path destination, BooleanSupplier cancelled)
			throws IOException, InterruptedException {
		runBinary(downloadArguments(repo, artifactId), destination, cancelled);
	}

	static List<String> downloadArguments(String repo, long artifactId) {
		return List.of("api", "repos/" + repo + "/actions/artifacts/" + artifactId + "/zip");
	}

	public static void delete(String repo, long artifactId, AtomicBoolean cancelled)
			throws IOException, InterruptedException {
		Gh.run(deleteArguments(repo, artifactId), cancelled);
	}

	static List<String> deleteArguments(String repo, long artifactId) {
		return List.of("api", "--method", "DELETE",
				"repos/" + repo + "/actions/artifacts/" + artifactId);
	}

	/** Parse the object (or slurped array of page objects) returned by the artifacts endpoint. */
	static List<Artifact> parseArtifacts(String json) throws IOException {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		JsonNode root = MAPPER.readTree(json);
		var artifacts = new ArrayList<Artifact>();
		if (root.isArray()) {
			root.forEach(page -> collectPage(page, artifacts));
		} else {
			collectPage(root, artifacts);
		}
		artifacts.sort(Comparator.comparing(Artifact::getCreatedAt,
				Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(Artifact::getName, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(artifacts);
	}

	private static void collectPage(JsonNode page, List<Artifact> sink) {
		JsonNode entries = page == null ? null : page.path("artifacts");
		if (entries == null || !entries.isArray()) {
			return;
		}
		entries.forEach(node -> {
			long id = node.path("id").asLong(0);
			String name = node.path("name").asText("").strip();
			if (id <= 0 || name.isBlank()) {
				return;
			}
			Artifact artifact = new Artifact();
			artifact.setId(id);
			artifact.setName(name);
			artifact.setSizeInBytes(Math.max(0, node.path("size_in_bytes").asLong(0)));
			artifact.setExpired(node.path("expired").asBoolean(false));
			artifact.setCreatedAt(localTime(node.path("created_at").asText("")));
			artifact.setUpdatedAt(localTime(node.path("updated_at").asText("")));
			artifact.setExpiresAt(localTime(node.path("expires_at").asText("")));
			JsonNode run = node.path("workflow_run");
			artifact.setRunId(run.path("id").asLong(0));
			artifact.setBranch(run.path("head_branch").asText(""));
			sink.add(artifact);
		});
	}

	private static LocalDateTime localTime(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
		} catch (DateTimeException e) {
			return null;
		}
	}

	private static NuclrResource upToRepository(String repo) {
		var repository = new GitHubRepos.Repository();
		repository.setNameWithOwner(repo);
		NuclrResource up = new RepoResource(repository);
		up.setName("..");
		up.setFolder(true);
		up.getMetadata().put(GitHubRepos.RepositoryName, repo);
		up.getMetadata().put(ArtifactResource.NameColumn, "..");
		return up;
	}

	/** Stream binary stdout from gh to disk while remaining cancellable during stalled reads. */
	private static void runBinary(List<String> args, Path destination, BooleanSupplier cancelled)
			throws IOException, InterruptedException {
		if (isCancelled(cancelled)) {
			throw new GhCancelledException("GitHub CLI command was cancelled");
		}
		var command = new ArrayList<String>();
		command.add("gh");
		command.addAll(args);
		Process process = new ProcessBuilder(command).start();
		process.getOutputStream().close();

		StringBuilder stderr = new StringBuilder();
		Thread stderrPump = new Thread(() -> drain(process.getErrorStream(), stderr), "gh-artifact-stderr");
		stderrPump.setDaemon(true);
		stderrPump.start();
		Thread watchdog = startWatchdog(process, cancelled);

		try {
			byte[] buffer = new byte[64 * 1024];
			try (InputStream input = process.getInputStream();
					var output = Files.newOutputStream(destination, StandardOpenOption.TRUNCATE_EXISTING)) {
				for (int read; (read = input.read(buffer)) >= 0;) {
					if (isCancelled(cancelled)) {
						throw new GhCancelledException("GitHub CLI command was cancelled");
					}
					if (read > 0) {
						output.write(buffer, 0, read);
					}
				}
			}
			int exit = process.waitFor();
			stderrPump.join();
			if (isCancelled(cancelled)) {
				throw new GhCancelledException("GitHub CLI command was cancelled");
			}
			if (exit != 0) {
				throw new IOException("gh " + String.join(" ", args) + " exited " + exit
						+ (stderr.isEmpty() ? "" : ": " + stderr.toString().strip()));
			}
		} catch (IOException | InterruptedException | RuntimeException e) {
			Gh.terminate(process);
			joinQuietly(stderrPump);
			throw e;
		} finally {
			if (watchdog != null) {
				watchdog.interrupt();
				joinQuietly(watchdog);
			}
		}
	}

	private static Thread startWatchdog(Process process, BooleanSupplier cancelled) {
		if (cancelled == null) {
			return null;
		}
		Thread watchdog = new Thread(() -> {
			try {
				while (!process.waitFor(WATCHDOG_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
					if (isCancelled(cancelled)) {
						Gh.terminate(process);
						return;
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "gh-artifact-cancel");
		watchdog.setDaemon(true);
		watchdog.start();
		return watchdog;
	}

	private static boolean isCancelled(BooleanSupplier cancelled) {
		return cancelled != null && cancelled.getAsBoolean();
	}

	private static void drain(InputStream input, StringBuilder sink) {
		try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				sink.append(line).append('\n');
			}
		} catch (IOException ignored) {
			// Best-effort diagnostics only.
		}
	}

	private static void joinQuietly(Thread thread) {
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Data
	public static class Artifact {
		private long id;
		private String name;
		private long sizeInBytes;
		private boolean expired;
		private LocalDateTime createdAt;
		private LocalDateTime updatedAt;
		private LocalDateTime expiresAt;
		private long runId;
		private String branch;
	}
}
