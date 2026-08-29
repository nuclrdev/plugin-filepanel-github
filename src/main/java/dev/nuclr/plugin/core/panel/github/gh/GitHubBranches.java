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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.NuclrResourceData;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.ResourcesHelper;
import dev.nuclr.plugin.core.panel.github.model.BranchResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class GitHubBranches {

	public static final String BranchName = "Branches";

	private GitHubBranches() {
	}

	public static NuclrResourceData branches(NuclrResource repository) {
		return branches(repository, null);
	}

	public static NuclrResourceData branches(NuclrResource repository, AtomicBoolean cancelled) {

		var data = new NuclrResourceData();

		// Clone repository metadata to avoid modifying the original resource
		var up = ResourcesHelper.root();
		up.setName("..");
		// The host sorts directories-first then by name with no special-casing for
		// "..", so the parent entry only lands on top when it is itself a folder.
		// RootResource doesn't set this, so force it here.
		up.setFolder(true);
		up.getMetadata().put(BranchName, "..");
		data.getEntries().add(up);

		data.setColumnNames(List.of(BranchName));

		var repo = repository.getMetadata(GitHubRepos.RepositoryName, "");
		if (repo.isBlank()) {
			log.warn("Cannot list branches: the resource carries no repository name");
			return data;
		}

		try {
			for (var branch : listBranches(repo, cancelled)) {
				data.getEntries().add(new BranchResource(repo, branch));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Branch listing for {} was interrupted", repo);
		} catch (GhCancelledException e) {
			log.debug("Branch listing for {} was cancelled", repo);
		} catch (Exception e) {
			log.error("Failed to list branches for repo {}: {}", repo, e.getMessage());
		}

		return data;
	}

	/**
	 * List every branch name in the repository. {@code --jq} emits one name per
	 * line on stdout while gh's own diagnostics stay on stderr, which
	 * {@link Gh#run} keeps out of the parsed result.
	 */
	private static List<String> listBranches(String repo, AtomicBoolean cancelled) throws Exception {

		String output = Gh.run(
				List.of("api", "repos/" + repo + "/branches", "--paginate", "--jq", ".[].name"),
				cancelled);

		return parseBranchNames(output);
	}

	static List<String> parseBranchNames(String output) {
		if (output == null || output.isBlank()) {
			return List.of();
		}
		return output.lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty())
				.toList();
	}
}
