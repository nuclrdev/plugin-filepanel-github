package dev.nuclr.plugin.core.panel.github;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.gh.Gh;
import dev.nuclr.plugin.core.panel.github.gh.GitHubRepos;
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
	private static final String PluginName = "Github Plugin";
	private static final String PluginVersion = "1.0.0";
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
	public boolean supports(Path path) {

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
		
		/*

		var rootName = ResourcesHelper.root().getPath().toString();

		return path.toString().equals(rootName) || path.startsWith(Path.of(rootName));
		*/
		
		return false;
		
	}

	private void showError(String title, String message) {
		JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		
		if (resourceToOpen.equals(ResourcesHelper.root())) {
			this.selectedResource = resourceToOpen;
			return GitHubRepos.repos();
		}
		
		return new NuclrResourceData();
	}

	@Override
	public String getCurrentLocationDisplayText() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		// TODO Auto-generated method stub
		return null;
	}

}
