package dev.nuclr.plugin.core.panel.github;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.gh.BranchSource;
import dev.nuclr.plugin.core.panel.github.gh.Gh;
import dev.nuclr.plugin.core.panel.github.gh.GitHubBranches;
import dev.nuclr.plugin.core.panel.github.gh.GitHubClone;
import dev.nuclr.plugin.core.panel.github.gh.GitHubRepos;
import dev.nuclr.plugin.core.panel.github.gh.GitHubSourceListing;
import dev.nuclr.plugin.core.panel.github.model.BranchResource;
import dev.nuclr.plugin.core.panel.github.model.SourceResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal GitHub file-panel provider that materialises a read-only navigation
 * tree backed by GitHub CLI commands.
 *
 * <pre>
 * GitHub
 *   Repositories
 *     owner/repo
 *       Info
 *         README.txt
 * </pre>
 */
@Slf4j
public class GithubFilePanelProvider implements FilePanelNuclrPlugin {
	
	private static final String CloneAction = "github.branch.clone";

	private boolean focused = false;
	private NuclrPluginContext context;
	
	/**
	 * UUIDs of the instances the host has actually started, which share
	 * {@link BranchSource}'s static cache. Only instances that reach init() register
	 * here: the template is probed via supports() and left at preinit forever, so
	 * counting it would mean the set never empties and the cache never got dropped.
	 * Keying by UUID also makes repeated init()/unload() calls idempotent.
	 */
	private static final java.util.Set<String> LIVE_INSTANCES =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	private boolean initialisedAndAuthenticated = false;

	/**
	 * Set once the gh probes have run, whether they succeeded or not. supports() is
	 * called on every navigation, so without this latch a failed check would
	 * re-spawn the gh probes and re-raise the modal warning on each keystroke.
	 */
	private boolean ghProbed = false;

	private final String uuid = java.util.UUID.randomUUID().toString();
	
	private NuclrResource selectedResource;
	

	@Override
	public boolean onFocusGained() {
		this.focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		this.focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}
	
	@Override
	public MenuItemsHolder getPluginMenuItems() {
		
		var holder = new MenuItemsHolder();
		
		var menuItem = new MenuItem();
		menuItem.setPath(ResourcesHelper.root());
		menuItem.setText("GitHub");
		menuItem.setUuid("gh_root");
		
		holder.setTitle("GitHub Repositories");
		holder.setMenuItems(List.of(menuItem));
		
		return holder;
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {
		// While branches are displayed, selectedResource is the repository that was
		// opened to produce the list. Register F5 for that stable panel context as
		// well as for a branch under the cursor; act() still refuses to run unless
		// it receives a real BranchResource.
		return branchResource(resource) != null
				|| hasTag(selectedResource, "github-repo")
				|| branchResource(selectedResource) != null
				? List.of(new NuclrMenuResource("Clone", "F5", CloneAction))
				: List.of();
	}

	@Override
	public void init() {
		// Only the host calls init(), and only on a live instance, so this is the one
		// place an instance may claim a share of the cache. supports() deliberately
		// uses probeGh() instead: it also runs on the never-unloaded template.
		LIVE_INSTANCES.add(uuid);
		probeGh();
	}

	/** Run the gh availability/auth checks once, warning the user at most once. */
	private void probeGh() {

		if (ghProbed) {
			return;
		}
		ghProbed = true;

		if (false == Gh.isGhInstalled()) {
			showError("GitHub CLI not found", "The GitHub file panel plugin requires the GitHub CLI to be installed. Please install the GitHub CLI to use this plugin.");
			log.info("GitHub CLI not found");
			return;
		}

		if (false == Gh.isGhAuthenticated()) {
			showError("GitHub CLI not authenticated", "The GitHub file panel plugin requires the user to be authenticated with the GitHub CLI. Please authenticate with the GitHub CLI to use this plugin.");
			log.info("GitHub CLI not authenticated");
			return;
		}

		initialisedAndAuthenticated = true;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public void unload() {
		// Drop the shared archive cache only once the last started panel has gone, so
		// unloading one panel cannot force the other to re-download a branch it is
		// still browsing.
		if (LIVE_INSTANCES.remove(uuid) && LIVE_INSTANCES.isEmpty()) {
			BranchSource.clear();
		}
		closeResource();
		context = null;
		initialisedAndAuthenticated = false;
		ghProbed = false;
	}

	@Override
	public void closeResource() {
		selectedResource = null;
	}

	@Override
	public NuclrResource getCurrentResource() {
		return this.selectedResource;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		
		var path = resource.getPath();

		// supports() is probed on the TEMPLATE instance (preinit only), BEFORE any
		// live instance exists, so it must decide purely from the path and must NOT
		// depend on init() state. Returning true here is what causes the registry to
		// build a live instance and finally call init().
		if (path == null) {
			return false;
		}
		
		// Probe gh if necessary. probeGh() latches internally, so a missing or
		// unauthenticated gh is reported once rather than on every navigation, and it
		// does not register this (possibly template) instance as a cache holder.
		if (false == initialisedAndAuthenticated && path.equals(ResourcesHelper.root().getPath())) {
			probeGh();
			if (false == initialisedAndAuthenticated) {
				return false;
			}
		}
		
		// Root?
		if (path.equals(ResourcesHelper.root().getPath())) {
			return true;
		}
		
		// Navigable resources are identified by their path tag (filename). Source
		// files use a distinct tag and are intentionally NOT navigable.
		var tag = path.getFileName() != null ? path.getFileName().toString() : null;
		if (tag == null) {
			return false;
		}

		switch (tag) {
			case "github-repo":              // repository -> branches
			case BranchResource.Tag:         // branch -> source root
			case SourceResource.DirTag:      // source directory -> children
				return true;
			default:
				return false;
		}
	}

	private void showError(String title, String message) {
		Runnable show = () -> JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
		if (SwingUtilities.isEventDispatchThread()) {
			show.run();
		} else {
			SwingUtilities.invokeLater(show);
		}
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {

		// List repos
		if (resourceToOpen.equals(ResourcesHelper.root())) {
			this.selectedResource = resourceToOpen;
			return GitHubRepos.repos(cancelled);
		}

		var path = resourceToOpen.getPath();
		var tag = path != null && path.getFileName() != null ? path.getFileName().toString() : null;

		if (tag != null) {
			switch (tag) {

				// Repository -> its branches
				case "github-repo":
					this.selectedResource = resourceToOpen;
					return GitHubBranches.branches(resourceToOpen, cancelled);

				// Branch -> fetch (once) + cache full source, list root directory
				case BranchResource.Tag:
					this.selectedResource = resourceToOpen;
					return GitHubSourceListing.openBranch(resourceToOpen, cancelled);

				// Source directory -> list children from the cached tree
				case SourceResource.DirTag:
					this.selectedResource = resourceToOpen;
					return GitHubSourceListing.openDirectory(resourceToOpen, cancelled);

				default:
					break;
			}
		}

		return new NuclrResourceData();
	}

	@Override
	public String getCurrentLocationDisplayText() {

		if (selectedResource == null) {
			return "GitHub";
		}

		var path = selectedResource.getPath();
		var tag = path != null && path.getFileName() != null ? path.getFileName().toString() : null;

		if (BranchResource.Tag.equals(tag)) {
			var repo = selectedResource.getMetadata(BranchResource.Repo, "");
			var branch = selectedResource.getMetadata(GitHubBranches.BranchName, "");
			return "GitHub: " + repo + " @ " + branch;
		}

		if (SourceResource.DirTag.equals(tag)) {
			var repo = selectedResource.getMetadata(SourceResource.Repo, "");
			var branch = selectedResource.getMetadata(SourceResource.Branch, "");
			var srcPath = selectedResource.getMetadata(SourceResource.SourcePath, "");
			return "GitHub: " + repo + " @ " + branch + "/" + srcPath;
		}

		return "GitHub";
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void act(
			BaseNuclrPlugin other,
			String actionType,
			List<NuclrResource> selectedResources,
			NuclrResource focusedResource,
			Map<String, Object> data,
			NuclrPluginCallback callback) {

		if (!CloneAction.equals(actionType)) {
			return;
		}

		NuclrResource branch = branchResource(focusedResource);
		if (branch == null && selectedResources != null) {
			branch = selectedResources.stream()
					.map(GithubFilePanelProvider::branchResource)
					.filter(java.util.Objects::nonNull)
					.findFirst()
					.orElse(null);
		}
		if (branch == null) {
			branch = branchResource(selectedResource);
		}

		Path destination = GitHubClone.destinationDirectory(other);
		if (branch == null || destination == null) {
			return;
		}

		String repo = branch.getMetadata(BranchResource.Repo, "");
		String branchName = branch.getMetadata(GitHubBranches.BranchName, "");
		if (repo.isBlank() || branchName.isBlank()) {
			return;
		}

		final String destinationPluginUuid = other.uuid();
		GitHubClone.cloneBranch(repo, branchName, destination, callback,
				() -> {
					if (context != null) {
						context.getEventBus().emit(
								"refresh.plugin.file.panel",
								Map.of("plugin.uuid", destinationPluginUuid),
								null);
					}
				},
				error -> showError("Clone failed", error.getMessage()));
	}

	private static NuclrResource branchResource(NuclrResource resource) {
		return hasTag(resource, BranchResource.Tag) ? resource : null;
	}

	private static boolean hasTag(NuclrResource resource, String tag) {
		return resource != null
				&& resource.getPath() != null
				&& resource.getPath().getFileName() != null
				&& tag.equals(resource.getPath().getFileName().toString());
	}


}
