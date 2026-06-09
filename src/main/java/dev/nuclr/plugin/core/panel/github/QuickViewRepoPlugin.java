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
package dev.nuclr.plugin.core.panel.github;

import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.panel.github.gh.GitHubRepos;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick-view provider for GitHub repository resources.
 *
 * <p>
 * When a repository (a {@code RepoResource}, identified by the {@code github-repo}
 * path tag) is previewed, this plugin shells out to the {@code gh} CLI to gather
 * a broad picture of the repo â€” description, owner, visibility, default branch,
 * stats (stars/forks/issues), topics, language breakdown, enabled features and
 * the latest release â€” and renders it into a scrollable HTML panel.
 *
 * <p>
 * The view is a {@link CardLayout} with an animated <em>loading</em> card and a
 * <em>content</em> card. {@link #openResource} returns immediately after showing
 * the loading card and runs the {@code gh} work on a background thread, narrating
 * progress before revealing the rendered HTML. Every {@code gh} call is
 * best-effort: a section is omitted if its call fails.
 */
@Slf4j
public final class QuickViewRepoPlugin implements QuickViewNuclrPlugin {

	public static final String PluginId = "dev.nuclr.plugin.core.panel.github.quickviewrepo";
	private static final String PluginName = "Github Plugin (Repository Quick View)";
	private static final String PluginVersion = loadVersion();
	private static final String PluginDescription = "A quick view plugin for GitHub repositories.";
	private static final String PluginAuthor = "Nuclr Development Team";
	private static final String PluginLicense = "Apache-2.0";
	private static final String PluginWebsite = "https://nuclr.dev";
	private static final String PluginPageUrl = "https://nuclr.dev/plugins/core/filepanel-github.html";
	private static final String PluginDocUrl = PluginPageUrl;

	/** Path tag identifying a repository resource (see {@code RepoResource}). */
	private static final String REPO_TAG = "github-repo";

	private static final String CARD_LOADING = "loading";
	private static final String CARD_CONTENT = "content";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private NuclrPluginContext context;
	private NuclrThemeScheme theme;
	private NuclrResource currentResource;
	private volatile AtomicBoolean currentCancelled;

	private JPanel container;
	private CardLayout cardLayout;

	private JScrollPane scrollPane;
	private JEditorPane editor;

	// Loading card widgets.
	private JProgressBar progressBar;
	private JLabel loadingTitle;
	private JLabel loadingSubtitle;
	private JLabel loadingStatus;

	// ---------------------------------------------------------------- panel ---

	@Override
	public JComponent panel() {
		if (container == null) {
			cardLayout = new CardLayout();
			container = new JPanel(cardLayout);
			container.add(buildLoadingCard(), CARD_LOADING);
			container.add(buildContentCard(), CARD_CONTENT);
		}
		return container;
	}

	private JComponent buildContentCard() {
		editor = new JEditorPane();
		editor.setEditable(false);
		editor.setContentType("text/html");
		editor.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		editor.setBackground(uiColor("Panel.background", new Color(43, 43, 43)));
		editor.addHyperlinkListener(e -> {
			if (HyperlinkEvent.EventType.ACTIVATED == e.getEventType() && e.getURL() != null) {
				openInBrowser(e.getURL().toString());
			}
		});
		scrollPane = new JScrollPane(editor);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		scrollPane.setBorder(null);
		return scrollPane;
	}

	/** A centred, animated loading card: repo title, owner subtitle, indeterminate bar, live status. */
	private JComponent buildLoadingCard() {

		Color bg = uiColor("Panel.background", new Color(43, 43, 43));
		Color fg = uiColor("Label.foreground", new Color(204, 204, 204));
		Color dim = blend(fg, bg, 0.45f);
		Color accent = uiColor("Component.focusColor", new Color(88, 157, 246));

		JPanel inner = new JPanel();
		inner.setOpaque(false);
		inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));

		JLabel glyph = centredLabel("â—ˆ", accent);
		glyph.setFont(glyph.getFont().deriveFont(Font.BOLD, 28f));

		loadingTitle = centredLabel("Loadingâ€¦", fg);
		loadingTitle.setFont(loadingTitle.getFont().deriveFont(Font.BOLD, 18f));

		loadingSubtitle = centredLabel(" ", dim);

		progressBar = new JProgressBar();
		progressBar.setIndeterminate(true);
		progressBar.setBorderPainted(false);
		progressBar.setPreferredSize(new Dimension(260, 6));
		progressBar.setMaximumSize(new Dimension(260, 6));
		progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);

		loadingStatus = centredLabel("Contacting GitHubâ€¦", dim);
		loadingStatus.setFont(loadingStatus.getFont().deriveFont(11f));

		inner.add(glyph);
		inner.add(Box.createVerticalStrut(12));
		inner.add(loadingTitle);
		inner.add(Box.createVerticalStrut(4));
		inner.add(loadingSubtitle);
		inner.add(Box.createVerticalStrut(18));
		inner.add(progressBar);
		inner.add(Box.createVerticalStrut(10));
		inner.add(loadingStatus);

		JPanel card = new JPanel(new GridBagLayout()); // GridBag with one child centres it
		card.setBackground(bg);
		card.add(inner);
		return card;
	}

	private static JLabel centredLabel(String text, Color color) {
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(color);
		label.setAlignmentX(Component.CENTER_ALIGNMENT);
		return label;
	}

	// ------------------------------------------------------------- lifecycle ---

	@Override
	public boolean supports(NuclrResource resource) {
		if (resource == null) {
			return false;
		}
		var path = resource.getPath();
		if (path == null || path.getFileName() == null) {
			return false;
		}
		return REPO_TAG.equals(path.getFileName().toString());
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {

		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentResource = resource;
		currentCancelled = cancelled;
		panel();

		String repo = resource.getMetadata(GitHubRepos.RepositoryName, "");
		if (repo == null || repo.isBlank()) {
			repo = resource.getName();
		}

		// Need a full "owner/name" reference to address the repo via gh.
		if (repo == null || !repo.contains("/")) {
			return false;
		}

		final String frepo = repo;

		// Show the animated loading card immediately and return true so the host swaps
		// this panel in right away; the gh work then runs on its own thread.
		showLoading(frepo);

		Thread worker = new Thread(() -> {
			try {
				String html = buildHtml(frepo, cancelled, status -> updateLoadingStatus(status, cancelled));
				if (isCancelled(cancelled)) {
					return;
				}
				showContent(html);
			} catch (Exception e) {
				if (isCancelled(cancelled)) {
					return;
				}
				log.error("Failed to build repo quick view for {}: {}", frepo, e.getMessage(), e);
				showContent(errorHtml(frepo, e.getMessage()));
			}
		}, "gh-repo-qv");
		worker.setDaemon(true);
		worker.start();

		return true;
	}

	@Override
	public void closeResource() {
		if (currentCancelled != null) {
			currentCancelled.set(true);
			currentCancelled = null;
		}
	}

	@Override
	public void unload() {
		closeResource();
		container = null;
		cardLayout = null;
		scrollPane = null;
		editor = null;
		progressBar = null;
		loadingTitle = null;
		loadingSubtitle = null;
		loadingStatus = null;
		context = null;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.theme = context != null ? context.getTheme() : null;
	}

	@Override
	public void init() {
	}

	@Override
	public NuclrPluginContext getContext() {
		return this.context;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		this.theme = themeScheme;
	}

	@Override
	public int priority() {
		return 1;
	}

	// --------------------------------------------------------- card switching ---

	private void showLoading(String repo) {
		String owner = repo.contains("/") ? repo.substring(0, repo.indexOf('/')) : "";
		String shortName = repo.contains("/") ? repo.substring(repo.indexOf('/') + 1) : repo;
		onEdt(() -> {
			if (loadingTitle != null) {
				loadingTitle.setText(shortName);
			}
			if (loadingSubtitle != null) {
				loadingSubtitle.setText(owner.isBlank() ? repo : owner);
			}
			if (loadingStatus != null) {
				loadingStatus.setText("Contacting GitHubâ€¦");
			}
			if (progressBar != null) {
				progressBar.setIndeterminate(true);
			}
			if (cardLayout != null && container != null) {
				cardLayout.show(container, CARD_LOADING);
			}
		});
	}

	private void updateLoadingStatus(String status, AtomicBoolean cancelled) {
		if (isCancelled(cancelled)) {
			return;
		}
		onEdt(() -> {
			if (loadingStatus != null) {
				loadingStatus.setText(status);
			}
		});
	}

	private void showContent(String html) {
		onEdt(() -> {
			if (editor != null) {
				editor.setText(html);
				editor.setCaretPosition(0);
			}
			if (progressBar != null) {
				progressBar.setIndeterminate(false);
			}
			if (cardLayout != null && container != null) {
				cardLayout.show(container, CARD_CONTENT);
			}
		});
	}

	// ------------------------------------------------------------- rendering ---

	private String buildHtml(String repo, AtomicBoolean cancelled, Consumer<String> progress) throws IOException {

		progress.accept("Fetching repository detailsâ€¦");
		JsonNode r = ghJson("repos/" + repo, cancelled);
		if (r == null || r.path("full_name").isMissingNode()) {
			throw new IOException("Could not read repository '" + repo + "' via gh.");
		}

		String fullName = text(r, "full_name");
		StringBuilder sb = new StringBuilder(4096);
		sb.append(htmlHead());
		sb.append("<h1>").append(esc(fullName.isBlank() ? repo : fullName)).append("</h1>");
		String description = text(r, "description");
		sb.append("<div class='sub'>").append(description.isBlank() ? "&nbsp;" : esc(description)).append("</div>");

		// --- Overview --------------------------------------------------------
		sb.append(section("Overview"));
		sb.append("<table>");
		rowOptional(sb, "Owner", text(r.path("owner"), "login")
				+ (text(r.path("owner"), "type").isBlank() ? "" : " (" + text(r.path("owner"), "type") + ")"));
		rowOptional(sb, "Visibility", capitalize(visibility(r)));
		rowOptional(sb, "Default branch", text(r, "default_branch"));
		rowOptional(sb, "Primary language", text(r, "language"));
		rowOptional(sb, "License", text(r.path("license"), "name"));
		String status = repoStatus(r);
		rowOptional(sb, "Status", status);
		String homepage = text(r, "homepage");
		if (!homepage.isBlank()) {
			rowRaw(sb, "Homepage", link(homepage));
		}
		rowOptional(sb, "Size", humanKb(r.path("size").asLong(0)));
		rowOptional(sb, "Created", fmtDate(text(r, "created_at")));
		rowOptional(sb, "Updated", fmtDate(text(r, "updated_at")));
		rowOptional(sb, "Last push", fmtDate(text(r, "pushed_at")));
		String url = text(r, "html_url");
		if (!url.isBlank()) {
			rowRaw(sb, "URL", link(url));
		}
		sb.append("</table>");

		// --- Fork parent -----------------------------------------------------
		if (r.path("fork").asBoolean(false) && r.path("parent").isObject()) {
			JsonNode parent = r.path("parent");
			sb.append(section("Forked from"));
			sb.append("<table>");
			String parentUrl = text(parent, "html_url");
			String parentName = text(parent, "full_name");
			rowRaw(sb, "Parent", parentUrl.isBlank() ? esc(parentName)
					: "<a href='" + esc(parentUrl) + "'>" + esc(parentName) + "</a>");
			rowOptional(sb, "Default branch", text(parent, "default_branch"));
			sb.append("</table>");
		}

		// --- Stats -----------------------------------------------------------
		sb.append(section("Statistics"));
		sb.append("<table>");
		row(sb, "Stars", count(r, "stargazers_count"));
		row(sb, "Watchers", count(r, "subscribers_count"));
		row(sb, "Forks", count(r, "forks_count"));
		row(sb, "Open issues & PRs", count(r, "open_issues_count"));
		sb.append("</table>");

		// --- Topics ----------------------------------------------------------
		JsonNode topics = r.path("topics");
		if (topics.isArray() && topics.size() > 0) {
			List<String> names = new ArrayList<>();
			topics.forEach(t -> names.add(t.asText()));
			sb.append(section("Topics"));
			sb.append("<p>").append(esc(String.join(", ", names))).append("</p>");
		}

		// --- Languages -------------------------------------------------------
		if (!isCancelled(cancelled)) {
			progress.accept("Reading language breakdownâ€¦");
			JsonNode langs = ghJson("repos/" + repo + "/languages", cancelled);
			if (langs != null && langs.isObject() && langs.size() > 0) {
				sb.append(section("Languages"));
				sb.append("<table>");
				appendLanguages(sb, langs);
				sb.append("</table>");
			}
		}

		// --- Features --------------------------------------------------------
		List<String> features = new ArrayList<>();
		if (r.path("has_issues").asBoolean(false)) features.add("Issues");
		if (r.path("has_projects").asBoolean(false)) features.add("Projects");
		if (r.path("has_wiki").asBoolean(false)) features.add("Wiki");
		if (r.path("has_pages").asBoolean(false)) features.add("Pages");
		if (r.path("has_discussions").asBoolean(false)) features.add("Discussions");
		if (!features.isEmpty()) {
			sb.append(section("Features"));
			sb.append("<p>").append(esc(String.join(", ", features))).append("</p>");
		}

		// --- Latest release --------------------------------------------------
		if (!isCancelled(cancelled)) {
			progress.accept("Checking latest releaseâ€¦");
			JsonNode release = ghJson("repos/" + repo + "/releases/latest", cancelled);
			if (release != null && !text(release, "tag_name").isBlank()) {
				sb.append(section("Latest release"));
				sb.append("<table>");
				String name = text(release, "name");
				row(sb, "Tag", text(release, "tag_name"));
				rowOptional(sb, "Name", name);
				rowOptional(sb, "Published", fmtDate(text(release, "published_at")));
				rowOptional(sb, "Author", text(release.path("author"), "login"));
				String relUrl = text(release, "html_url");
				if (!relUrl.isBlank()) {
					rowRaw(sb, "URL", link(relUrl));
				}
				sb.append("</table>");
			}
		}

		sb.append(htmlFoot());
		return sb.toString();
	}

	/** Render the languages object (name -> bytes) as percentage rows, largest first. */
	private void appendLanguages(StringBuilder sb, JsonNode langs) {

		long total = 0L;
		List<Map.Entry<String, Long>> entries = new ArrayList<>();
		var it = langs.fields();
		while (it.hasNext()) {
			var e = it.next();
			long bytes = e.getValue().asLong(0);
			entries.add(Map.entry(e.getKey(), bytes));
			total += bytes;
		}
		if (total <= 0) {
			return;
		}
		entries.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed());

		for (var e : entries) {
			double pct = e.getValue() * 100.0 / total;
			row(sb, e.getKey(), String.format("%.1f%%", pct));
		}
	}

	// -------------------------------------------------------------- gh calls ---

	/** Run {@code gh api <endpoint>} and parse the JSON, or {@code null} on any failure. */
	private JsonNode ghJson(String endpoint, AtomicBoolean cancelled) {
		if (isCancelled(cancelled)) {
			return null;
		}
		try {
			String out = runGh(List.of("api", endpoint), cancelled);
			return out.isBlank() ? null : MAPPER.readTree(out);
		} catch (Exception e) {
			log.warn("gh api {} failed: {}", endpoint, e.getMessage());
			return null;
		}
	}

	private static String runGh(List<String> args, AtomicBoolean cancelled)
			throws IOException, InterruptedException {

		List<String> command = new ArrayList<>(args.size() + 1);
		command.add("gh");
		command.addAll(args);

		Process process = new ProcessBuilder(command).start();

		// Drain stderr on a side thread so a chatty gh can't block on a full pipe.
		StringBuilder stderr = new StringBuilder();
		Thread pump = new Thread(() -> drain(process.getErrorStream(), stderr), "gh-repo-stderr");
		pump.setDaemon(true);
		pump.start();

		String stdout;
		try (InputStream in = process.getInputStream()) {
			stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}

		int exit = process.waitFor();
		pump.join();

		if (exit != 0) {
			throw new IOException("gh " + String.join(" ", args) + " exited " + exit + ": " + stderr.toString().strip());
		}
		return stdout;
	}

	private static void drain(InputStream in, StringBuilder sink) {
		try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				sink.append(line).append('\n');
			}
		} catch (IOException ignored) {
			// best-effort diagnostics only
		}
	}

	// ----------------------------------------------------------- html helpers ---

	private String htmlHead() {
		Color bg = uiColor("Panel.background", new Color(43, 43, 43));
		Color fg = uiColor("Label.foreground", new Color(204, 204, 204));
		Color accent = uiColor("Component.focusColor", new Color(88, 157, 246));
		String dim = toHex(blend(fg, bg, 0.45f));
		String rule = toHex(blend(fg, bg, 0.78f));
		return "<html><head><style>"
				+ "body{font-family:'Segoe UI',sans-serif;font-size:11px;margin:10px;color:" + toHex(fg) + ";}"
				+ "h1{font-size:16px;margin:0 0 2px 0;color:" + toHex(fg) + ";}"
				+ ".sub{color:" + dim + ";font-size:11px;margin-bottom:10px;}"
				+ "h2{font-size:12px;margin:16px 0 4px 0;color:" + toHex(accent)
				+ ";border-bottom:1px solid " + rule + ";padding-bottom:2px;}"
				+ "table{border-collapse:collapse;width:100%;}"
				+ "td{vertical-align:top;padding:2px 8px 2px 0;}"
				+ "td.k{color:" + dim + ";white-space:nowrap;width:140px;}"
				+ "a{color:" + toHex(accent) + ";text-decoration:none;}"
				+ "p{margin:4px 0;}"
				+ ".dim{color:" + dim + ";}"
				+ "</style></head><body>";
	}

	private String htmlFoot() {
		return "</body></html>";
	}

	private static String section(String title) {
		return "<h2>" + title + "</h2>";
	}

	private static void row(StringBuilder sb, String key, String value) {
		rowRaw(sb, esc(key), esc(value));
	}

	/** Like {@link #row} but skips the row entirely when the value is blank. */
	private static void rowOptional(StringBuilder sb, String key, String value) {
		if (value != null && !value.isBlank()) {
			rowRaw(sb, esc(key), esc(value));
		}
	}

	/** Append a row whose key/value are already HTML (not escaped further). */
	private static void rowRaw(StringBuilder sb, String keyHtml, String valueHtml) {
		sb.append("<tr><td class='k'>").append(keyHtml).append("</td><td>").append(valueHtml).append("</td></tr>");
	}

	private static String link(String url) {
		return "<a href='" + esc(url) + "'>" + esc(url) + "</a>";
	}

	private String errorHtml(String repo, String message) {
		return htmlHead()
				+ "<h1>" + esc(repo) + "</h1>"
				+ "<h2>Unable to load repository information</h2>"
				+ "<p>" + esc(message == null ? "Unknown error." : message) + "</p>"
				+ "<p class='dim'>Ensure the GitHub CLI (gh) is installed and authenticated.</p>"
				+ htmlFoot();
	}

	// --------------------------------------------------------------- utility ---

	private static String visibility(JsonNode repo) {
		String v = text(repo, "visibility");
		if (!v.isBlank()) {
			return v;
		}
		return repo.path("private").asBoolean(false) ? "private" : "public";
	}

	private static String repoStatus(JsonNode repo) {
		List<String> flags = new ArrayList<>();
		if (repo.path("fork").asBoolean(false)) flags.add("Fork");
		if (repo.path("archived").asBoolean(false)) flags.add("Archived");
		if (repo.path("disabled").asBoolean(false)) flags.add("Disabled");
		if (repo.path("is_template").asBoolean(false)) flags.add("Template");
		return flags.isEmpty() ? "Active" : String.join(", ", flags);
	}

	private static String count(JsonNode node, String field) {
		return String.format("%,d", node.path(field).asLong(0));
	}

	/** Format a size reported by the GitHub API in kibibytes. */
	private static String humanKb(long kb) {
		if (kb <= 0) {
			return "";
		}
		String[] units = {"KB", "MB", "GB", "TB"};
		double value = kb;
		int unit = 0;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format("%.1f %s", value, units[unit]);
	}

	private static String capitalize(String s) {
		if (s == null || s.isBlank()) {
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static void onEdt(Runnable r) {
		if (SwingUtilities.isEventDispatchThread()) {
			r.run();
		} else {
			SwingUtilities.invokeLater(r);
		}
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return (cancelled != null && cancelled.get()) || Thread.currentThread().isInterrupted();
	}

	private static String text(JsonNode node, String field) {
		if (node == null) {
			return "";
		}
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? "" : value.asText("");
	}

	private static String fmtDate(String iso) {
		if (iso == null || iso.isBlank()) {
			return "";
		}
		try {
			return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
					.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		} catch (Exception e) {
			return iso;
		}
	}

	private static String esc(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&#39;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	private void openInBrowser(String url) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(url));
			}
		} catch (Exception e) {
			log.warn("Failed to open {} in browser: {}", url, e.getMessage());
		}
	}

	private static Color uiColor(String key, Color fallback) {
		Color c = UIManager.getColor(key);
		return c != null ? c : fallback;
	}

	private static Color blend(Color a, Color b, float t) {
		float u = 1f - t;
		return new Color(
				Math.round(a.getRed() * u + b.getRed() * t),
				Math.round(a.getGreen() * u + b.getGreen() * t),
				Math.round(a.getBlue() * u + b.getBlue() * t));
	}

	private static String toHex(Color c) {
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	// --------------------------------------------------------------- focus ----

	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		return false;
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public String getWindowTitle() {
		return "GitHub Repository: " + (currentResource != null ? currentResource.getName() : "");
	}

	// ------------------------------------------------------------- metadata ---

	@Override
	public String id() {
		return PluginId;
	}

	@Override
	public String uuid() {
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
		try (var stream = QuickViewRepoPlugin.class.getResourceAsStream("/plugin.properties")) {
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

}
