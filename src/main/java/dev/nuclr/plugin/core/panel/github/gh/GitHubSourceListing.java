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
import dev.nuclr.plugin.core.panel.github.model.BranchResource;
import dev.nuclr.plugin.core.panel.github.model.RepoResource;
import dev.nuclr.plugin.core.panel.github.model.SourceNode;
import dev.nuclr.plugin.core.panel.github.model.SourceResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the file-panel listing for a directory within a branch's cached source
 * tree, including a {@code ".."} entry that navigates back up the hierarchy.
 */
@Slf4j
public final class GitHubSourceListing {

	private GitHubSourceListing() {
	}

	/**
	 * Open a branch: ensure its full source is fetched/cached, then list the root
	 * directory. The {@code ".."} entry returns to the repository's branch list.
	 */
	public static NuclrResourceData openBranch(NuclrResource branch) {
		return openBranch(branch, null);
	}

	/**
	 * Open a branch: ensure its full source is fetched/cached, then list the root
	 * directory. The {@code ".."} entry returns to the repository's branch list.
	 */
	public static NuclrResourceData openBranch(NuclrResource branch, AtomicBoolean cancelled) {

		String repo = branch.getMetadata(BranchResource.Repo, "");
		String branchName = branch.getMetadata(GitHubBranches.BranchName, "");

		try {
			SourceNode root = BranchSource.getOrFetch(repo, branchName, cancelled);
			NuclrResource up = upToBranches(repo);
			return list(repo, branchName, root, up);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Source fetch for {}@{} was interrupted", repo, branchName);
			return new NuclrResourceData();
		} catch (GhCancelledException e) {
			log.debug("Source fetch for {}@{} was cancelled", repo, branchName);
			return new NuclrResourceData();
		} catch (Exception e) {
			log.error("Failed to fetch source for {}@{}: {}", repo, branchName, e.getMessage(), e);
			return new NuclrResourceData();
		}
	}

	/**
	 * Open a directory within an already-cached branch source tree. The {@code ".."}
	 * entry returns to the parent directory, or to the branch root when at depth 1.
	 */
	public static NuclrResourceData openDirectory(NuclrResource dir) {
		return openDirectory(dir, null);
	}

	/**
	 * Open a directory within an already-cached branch source tree. The {@code ".."}
	 * entry returns to the parent directory, or to the branch root when at depth 1.
	 */
	public static NuclrResourceData openDirectory(NuclrResource dir, AtomicBoolean cancelled) {

		String repo = dir.getMetadata(SourceResource.Repo, "");
		String branchName = dir.getMetadata(SourceResource.Branch, "");
		String path = dir.getMetadata(SourceResource.SourcePath, "");

		SourceNode root = BranchSource.peek(repo, branchName);
		if (root == null) {
			// Cache evicted/lost — re-open the branch to repopulate it.
			return openBranch(new BranchResource(repo, branchName), cancelled);
		}

		SourceNode node = root.find(path);
		if (node == null || !node.isDirectory()) {
			log.warn("Source directory not found in cache: {}@{}:{}", repo, branchName, path);
			return new NuclrResourceData();
		}

		return list(repo, branchName, node, upFrom(repo, branchName, path));
	}

	private static NuclrResourceData list(String repo, String branch, SourceNode dir, NuclrResource up) {

		var data = new NuclrResourceData();
		data.setColumnNames(List.of(SourceResource.NameColumn, SourceResource.SizeColumn));

		data.getEntries().add(up);

		for (SourceNode child : dir.sortedChildren()) {
			data.getEntries().add(new SourceResource(repo, branch, child));
		}

		return data;
	}

	/** A {@code ".."} entry that re-opens the repository's branch list. */
	private static NuclrResource upToBranches(String repo) {
		var repository = new GitHubRepos.Repository();
		repository.setNameWithOwner(repo);
		NuclrResource up = new RepoResource(repository);
		markAsParent(up);
		// markAsParent's setName("..") routes through RepoResource.setName, which would
		// overwrite the repository name metadata that branches() reads — restore it.
		up.getMetadata().put(GitHubRepos.RepositoryName, repo);
		return up;
	}

	/** A {@code ".."} entry that re-opens the parent directory (or branch root). */
	private static NuclrResource upFrom(String repo, String branch, String path) {
		int slash = path.lastIndexOf('/');
		if (slash < 0) {
			// Top-level directory: parent is the branch root listing.
			NuclrResource up = new BranchResource(repo, branch);
			markAsParent(up);
			return up;
		}
		String parentPath = path.substring(0, slash);
		SourceNode root = BranchSource.peek(repo, branch);
		SourceNode parent = root == null ? null : root.find(parentPath);
		if (parent == null) {
			NuclrResource up = new BranchResource(repo, branch);
			markAsParent(up);
			return up;
		}
		NuclrResource up = new SourceResource(repo, branch, parent);
		markAsParent(up);
		return up;
	}

	private static void markAsParent(NuclrResource up) {
		up.setName("..");
		// The host sorts directories-first then by name with no special-casing for
		// "..", so the parent entry only lands on top when it is itself a folder.
		// RepoResource (used for the branch-root "..") doesn't set this, so force it.
		up.setFolder(true);
		up.getMetadata().put(SourceResource.NameColumn, "..");
		up.getMetadata().put(SourceResource.SizeColumn, "");
	}
}
