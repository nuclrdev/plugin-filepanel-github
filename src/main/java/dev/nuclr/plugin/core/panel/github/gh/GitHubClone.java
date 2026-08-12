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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import lombok.extern.slf4j.Slf4j;

/** Clones one GitHub branch into a local directory exposed by the other file panel. */
@Slf4j
public final class GitHubClone {

	private GitHubClone() {
	}

	/**
	 * Resolve a safe clone destination from the opposite panel.
	 *
	 * <p>A destination must be a file panel currently showing a writable directory
	 * on the default filesystem. Virtual/archive paths and non-file panels are
	 * deliberately rejected.
	 */
	public static Path destinationDirectory(BaseNuclrPlugin other) {
		if (other == null || !other.is(BaseNuclrPlugin.Type.FilePanel)) {
			return null;
		}

		var current = other.getCurrentResource();
		Path destination = current != null ? current.getPath() : null;
		if (destination == null
				|| destination.getFileSystem() != FileSystems.getDefault()
				|| !Files.isDirectory(destination)
				|| !Files.isWritable(destination)) {
			return null;
		}

		return destination;
	}

	/** Start a single-branch clone without blocking Commander's UI thread. */
	public static void cloneBranch(
			String repo,
			String branch,
			Path destination,
			NuclrPluginCallback callback,
			Runnable onSuccess,
			Consumer<Exception> onError) {

		Thread worker = new Thread(() -> {
			String description = repo + " @ " + branch;
			try {
				if (callback != null) {
					callback.onStart(description);
				}

				runClone(repo, branch, destination);

				if (callback != null) {
					callback.onComplete();
				}
				if (onSuccess != null) {
					onSuccess.run();
				}
			} catch (Exception e) {
				log.error("Failed to clone {} into {}: {}", description, destination, e.getMessage(), e);
				if (callback != null) {
					callback.onError(description, e);
				}
				if (onError != null) {
					onError.accept(e);
				}
			}
		}, "github-branch-clone");
		worker.setDaemon(true);
		worker.start();
	}

	static void runClone(String repo, String branch, Path destination) throws IOException, InterruptedException {
		Path cloneDirectory = destination.resolve(repositoryName(repo));
		List<String> command = List.of(
				"gh", "repo", "clone",
				httpsCloneUrl(repo),
				cloneDirectory.toAbsolutePath().toString(),
				"--",
				"--branch", branch,
				"--single-branch");

		Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.start();

		String output;
		try (var stream = process.getInputStream()) {
			output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
		}

		int exitCode;
		try {
			exitCode = process.waitFor();
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw e;
		}

		if (exitCode != 0) {
			throw new IOException(output.isBlank()
					? "gh exited with code " + exitCode + " while cloning " + repo + " @ " + branch
					: output);
		}
	}

	static String repositoryName(String repo) {
		int separator = repo.lastIndexOf('/');
		String name = separator >= 0 ? repo.substring(separator + 1) : repo;
		return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
	}

	static String httpsCloneUrl(String repo) {
		String normalized = repo.strip();
		if (normalized.startsWith("https://") || normalized.startsWith("http://")) {
			return normalized;
		}
		return "https://github.com/" + normalized + (normalized.endsWith(".git") ? "" : ".git");
	}
}
