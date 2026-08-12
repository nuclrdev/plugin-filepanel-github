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
	
	public static final String PluginId = "dev.nuclr.plugin.core.panel.github";
	private static final String CloneAction = "github.branch.clone";
	private static final String PluginName = "Github Plugin";
	private static final String PluginVersion = loadVersion();
	private static final String PluginDescription = "A plugin that provides a file panel for browsing GitHub resources using the GitHub CLI.";
	private static final String PluginAuthor = "Nuclr Development Team";
	private static final String PluginLicense = "Apache-2.0";
	private static final String PluginWebsite = "https://nuclr.dev";
	private static final String PluginPageUrl = "https://nuclr.dev/plugins/core/filepanel-github.html";
	private static final String PluginDocUrl = PluginPageUrl;	

	private boolean focused = false;
	private NuclrPluginContext context;
	
	private boolean initialisedAndAuthenticated = false;
	
	private String uuid = java.util.UUID.randomUUID().toString();
	
	private NuclrResource selectedResource;
	
	@Override
	public String id() {
		return PluginId;
	}

	@Override
	public String name() {
		return PluginName;
	}

	@Override
	public String version() {
		return PluginVersion;
	}
	private static String loadVersion() {
		try (var stream = GithubFilePanelProvider.class.getResourceAsStream("/plugin.properties")) {
			if (stream == null) return "unknown";
			var props = new java.util.Properties();
			props.load(stream);
			return props.getProperty("version", "unknown");
		} catch (java.io.IOException e) {
			return "unknown";
		}
	}

	@Override
	public String description() {
		return PluginDescription;
	}

	@Override
	public String author() {
		return PluginAuthor;
	}

	@Override
	public String license() {
		return PluginLicense;
	}

	@Override
	public String website() {
		return PluginWebsite;
	}

	@Override
	public String pageUrl() {
		return PluginPageUrl;
	}

	@Override
	public String docUrl() {
		return PluginDocUrl;
	}

	@Override
	public Developer developer() {
		return Developer.Official;
	}

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
		menuItem.setText("Github");
		menuItem.setUuid("gh_root");
		
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

		//  TODO: check if gh CLI is available, if not, show a warning and disable the plugin's functionality
		if (false == Gh.isGhInstalled()) {
			showError("GitHub CLI not found", "The GitHub file panel plugin requires the GitHub CLI to be installed. Please install the GitHub CLI to use this plugin.");
			log.info("GitHub CLI not found");
			return;
		}
		
		// TODO: check if the user is authenticated with gh CLI, if not, show a warning and disable the plugin's functionality
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
		// TODO Auto-generated method stub
		
	}

	@Override
	public void closeResource() {
		// TODO Auto-generated method stub
		
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
		
		// Init if necessary
		if (false == initialisedAndAuthenticated && path.equals(ResourcesHelper.root().getPath())) {
			init();
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
			return GitHubRepos.repos();
		}

		var path = resourceToOpen.getPath();
		var tag = path != null && path.getFileName() != null ? path.getFileName().toString() : null;

		if (tag != null) {
			switch (tag) {

				// Repository -> its branches
				case "github-repo":
					this.selectedResource = resourceToOpen;
					return GitHubBranches.branches(resourceToOpen);

				// Branch -> fetch (once) + cache full source, list root directory
				case BranchResource.Tag:
					this.selectedResource = resourceToOpen;
					return GitHubSourceListing.openBranch(resourceToOpen);

				// Source directory -> list children from the cached tree
				case SourceResource.DirTag:
					this.selectedResource = resourceToOpen;
					return GitHubSourceListing.openDirectory(resourceToOpen);

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
