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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.model.ArtifactResource;

/** Copy, move, and delete operations for selected Actions artifacts. */
public final class GitHubArtifactOperations {

	private GitHubArtifactOperations() {
	}

	public static List<NuclrResource> selectedArtifacts(
			List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		List<NuclrResource> candidates = selectedResources != null && !selectedResources.isEmpty()
				? selectedResources
				: focusedResource == null ? List.of() : List.of(focusedResource);
		return candidates.stream().filter(ArtifactResource::isArtifact).toList();
	}

	/** Download every artifact, deleting each remote artifact only after its ZIP is safely in place. */
	public static int transfer(List<NuclrResource> artifacts, Path destination, boolean move,
			NuclrPluginCallback callback) throws IOException, InterruptedException {
		int completed = 0;
		for (int index = 0; index < artifacts.size(); index++) {
			if (cancelled(callback)) {
				break;
			}
			NuclrResource artifact = artifacts.get(index);
			if (artifact.getMetadata(ArtifactResource.Expired, false)) {
				throw new IOException("Artifact has expired: " + artifact.getName());
			}
			String repo = artifact.getMetadata(ArtifactResource.Repo, "");
			long id = artifact.getMetadata(ArtifactResource.ArtifactId, 0L);
			if (repo.isBlank() || id <= 0) {
				throw new IOException("Artifact identity is missing: " + artifact.getName());
			}

			if (callback != null) {
				callback.onStart((move ? "Moving " : "Copying ") + artifact.getName()
						+ " (" + (index + 1) + "/" + artifacts.size() + ")");
				callback.onProgress(index, artifacts.size());
			}

			Path target = availableTarget(destination, artifact.getName(), id);
			Path part = Files.createTempFile(destination, ".nuclr-gh-artifact-", ".part");
			try {
				GitHubArtifacts.download(repo, id, part, () -> cancelled(callback));
				publish(part, target);
				if (move && !cancelled(callback)) {
					GitHubArtifacts.delete(repo, id, new AtomicBoolean(false));
				}
				completed++;
			} finally {
				Files.deleteIfExists(part);
			}
		}
		if (callback != null && !cancelled(callback)) {
			callback.onProgress(completed, artifacts.size());
			callback.onComplete();
		}
		return completed;
	}

	public static int delete(List<NuclrResource> artifacts, NuclrPluginCallback callback)
			throws IOException, InterruptedException {
		int deleted = 0;
		for (int index = 0; index < artifacts.size(); index++) {
			if (cancelled(callback)) {
				break;
			}
			NuclrResource artifact = artifacts.get(index);
			String repo = artifact.getMetadata(ArtifactResource.Repo, "");
			long id = artifact.getMetadata(ArtifactResource.ArtifactId, 0L);
			if (repo.isBlank() || id <= 0) {
				continue;
			}
			if (callback != null) {
				callback.onStart("Deleting " + artifact.getName()
						+ " (" + (index + 1) + "/" + artifacts.size() + ")");
				callback.onProgress(index, artifacts.size());
			}
			GitHubArtifacts.delete(repo, id, new AtomicBoolean(false));
			deleted++;
		}
		if (callback != null && !cancelled(callback)) {
			callback.onProgress(deleted, artifacts.size());
			callback.onComplete();
		}
		return deleted;
	}

	static Path availableTarget(Path destination, String requestedName, long artifactId) {
		String safeName = safeFileName(requestedName);
		Path preferred = destination.resolve(safeName);
		if (!Files.exists(preferred)) {
			return preferred;
		}
		int dot = safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
				? safeName.length() - 4 : safeName.length();
		String alternate = safeName.substring(0, dot) + "-" + artifactId + safeName.substring(dot);
		return destination.resolve(alternate);
	}

	static String safeFileName(String requestedName) {
		String name = requestedName == null ? "artifact.zip" : requestedName.strip();
		name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
		name = name.replaceAll("[. ]+$", "");
		if (name.isBlank()) {
			return "artifact.zip";
		}
		return name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? name : name + ".zip";
	}

	private static void publish(Path part, Path target) throws IOException {
		try {
			Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(part, target);
		}
	}

	private static boolean cancelled(NuclrPluginCallback callback) {
		return Thread.currentThread().isInterrupted() || callback != null && callback.isCancelled();
	}
}
