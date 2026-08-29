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
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.panel.github.gh.Gh;
import dev.nuclr.plugin.core.panel.github.gh.GitHubBranches;
import dev.nuclr.plugin.core.panel.github.model.BranchResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick-view provider for GitHub branch resources.
 *
 * <p>
 * When a branch (a {@link BranchResource}) is previewed, this plugin shells out
 * to the {@code gh} CLI to gather a broad picture of the branch — its head
 * commit, protection, how far it is ahead/behind the default branch, the diff
 * stats of the head commit, and any associated pull requests — and renders it
 * all into a scrollable HTML panel.
 *
 * <p>
 * The view is a {@link CardLayout} with an animated <em>loading</em> card and a
 * <em>content</em> card. {@link #openResource} returns immediately after showing
 * the loading card and kicks off the {@code gh} work on a background thread,
 * which narrates progress into the loading card and finally swaps in the
 * rendered HTML. Every {@code gh} call is best-effort: a section is simply
 * omitted if its call fails, so a partial picture is shown rather than nothing.
 */
@Slf4j
public final class QuickViewBranchPlugin implements QuickViewNuclrPlugin {

	public static final String PluginId = "dev.nuclr.plugin.core.panel.github.quickviewbranch";

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

	/** A centred, animated loading card: branch title, repo subtitle, indeterminate bar, live status. */
	private JComponent buildLoadingCard() {

		Color bg = uiColor("Panel.background", new Color(43, 43, 43));
		Color fg = uiColor("Label.foreground", new Color(204, 204, 204));
		Color dim = blend(fg, bg, 0.45f);
		Color accent = uiColor("Component.focusColor", new Color(88, 157, 246));

		JPanel inner = new JPanel();
		inner.setOpaque(false);
		inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));

		JLabel glyph = centredLabel("⌥", fg); // a small decorative mark
		glyph.setFont(glyph.getFont().deriveFont(Font.BOLD, 28f));
		glyph.setForeground(accent);

		loadingTitle = centredLabel("Loading…", fg);
		loadingTitle.setFont(loadingTitle.getFont().deriveFont(Font.BOLD, 18f));

		loadingSubtitle = centredLabel(" ", dim);

		progressBar = new JProgressBar();
		progressBar.setIndeterminate(true);
		progressBar.setBorderPainted(false);
		progressBar.setPreferredSize(new Dimension(260, 6));
		progressBar.setMaximumSize(new Dimension(260, 6));
		progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);

		loadingStatus = centredLabel("Contacting GitHub…", dim);
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
		return resource != null && Boolean.TRUE.equals(resource.getMetadata(BranchResource.Branch, false));
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {

		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentResource = resource;
		currentCancelled = cancelled;
		panel();

		String repo = resource.getMetadata(BranchResource.Repo, "");
		String branch = resource.getMetadata(GitHubBranches.BranchName, "");
		if (branch == null || branch.isBlank()) {
			branch = resource.getName();
		}

		// Not a usable branch reference (e.g. a ".." navigation entry) — let the host
		// fall back to its "no preview" provider.
		if (repo == null || repo.isBlank() || branch == null || branch.isBlank() || "..".equals(branch)) {
			return false;
		}

		final String frepo = repo;
		final String fbranch = branch;

		// Show the animated loading card immediately and return true so the host swaps
		// this panel in right away; the gh work then runs on its own thread, narrating
		// progress into the loading card before revealing the content.
		showLoading(frepo, fbranch);

		Thread worker = new Thread(() -> {
			try {
				String html = buildHtml(frepo, fbranch, cancelled, status -> updateLoadingStatus(status, cancelled));
				if (isCancelled(cancelled)) {
					return;
				}
				showContent(html);
			} catch (Exception e) {
				if (isCancelled(cancelled)) {
					return;
				}
				log.error("Failed to build branch quick view for {}@{}: {}", frepo, fbranch, e.getMessage(), e);
				showContent(errorHtml(frepo, fbranch, e.getMessage()));
			}
		}, "gh-branch-qv");
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


	// --------------------------------------------------------- card switching ---

	private void showLoading(String repo, String branch) {
		onEdt(() -> {
			if (loadingTitle != null) {
				loadingTitle.setText(branch);
			}
			if (loadingSubtitle != null) {
				loadingSubtitle.setText(repo);
			}
			if (loadingStatus != null) {
				loadingStatus.setText("Contacting GitHub…");
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

	private String buildHtml(String repo, String branch, AtomicBoolean cancelled, Consumer<String> progress)
			throws IOException {

		progress.accept("Fetching branch details…");
		JsonNode branchNode = ghJson("repos/" + repo + "/branches/" + branch, cancelled);
		if (branchNode == null || branchNode.path("name").isMissingNode()) {
			throw new IOException("Could not read branch '" + branch + "' in " + repo + " via gh.");
		}

		progress.accept("Reading repository…");
		JsonNode repoNode = ghJson("repos/" + repo, cancelled);
		String defaultBranch = text(repoNode, "default_branch");

		JsonNode commitWrapper = branchNode.path("commit");
		String sha = text(commitWrapper, "sha");
		JsonNode commit = commitWrapper.path("commit");

		StringBuilder sb = new StringBuilder(4096);
		sb.append(htmlHead());
		sb.append("<h1>").append(esc(branch)).append("</h1>");
		sb.append("<div class='sub'>").append(esc(repo)).append("</div>");

		// --- Overview --------------------------------------------------------
		sb.append(section("Overview"));
		sb.append("<table>");
		row(sb, "Repository", repo);
		row(sb, "Branch", branch);
		if (!defaultBranch.isBlank()) {
			boolean isDefault = defaultBranch.equals(branch);
			row(sb, "Default branch", defaultBranch + (isDefault ? "  ← this is the default" : ""));
		}
		row(sb, "Protected", yesNo(branchNode.path("protected").asBoolean(false)));
		if (!sha.isBlank()) {
			row(sb, "Head commit", shortSha(sha) + "  (" + sha + ")");
		}
		String headLogin = text(commitWrapper.path("author"), "login");
		if (!headLogin.isBlank()) {
			row(sb, "Head author (GitHub)", "@" + headLogin);
		}
		sb.append("</table>");

		// --- Latest commit ---------------------------------------------------
		if (!commit.isMissingNode()) {
			progress.accept("Analysing latest commit…");
			sb.append(section("Latest commit"));
			sb.append("<table>");
			JsonNode author = commit.path("author");
			JsonNode committer = commit.path("committer");
			row(sb, "Message", firstLine(text(commit, "message")));
			rowOptional(sb, "Author", joinNameEmail(text(author, "name"), text(author, "email")));
			rowOptional(sb, "Authored", fmtDate(text(author, "date")));
			rowOptional(sb, "Committer", joinNameEmail(text(committer, "name"), text(committer, "email")));
			rowOptional(sb, "Committed", fmtDate(text(committer, "date")));
			rowOptional(sb, "Signature", text(commit.path("verification"), "reason"));
			String url = text(commitWrapper, "html_url");
			if (!url.isBlank()) {
				rowRaw(sb, "URL", link(url));
			}
			sb.append("</table>");

			String fullMessage = text(commit, "message");
			if (fullMessage.contains("\n")) {
				sb.append("<pre class='msg'>").append(esc(fullMessage.strip())).append("</pre>");
			}
		}

		// --- Head commit diff stats -----------------------------------------
		if (!sha.isBlank() && !isCancelled(cancelled)) {
			progress.accept("Computing diff stats…");
			JsonNode commitDetail = ghJson("repos/" + repo + "/commits/" + sha, cancelled);
			if (commitDetail != null) {
				JsonNode stats = commitDetail.path("stats");
				sb.append(section("Head commit changes"));
				sb.append("<table>");
				row(sb, "Additions", "+" + stats.path("additions").asInt(0));
				row(sb, "Deletions", "−" + stats.path("deletions").asInt(0));
				row(sb, "Total lines", String.valueOf(stats.path("total").asInt(0)));
				JsonNode files = commitDetail.path("files");
				row(sb, "Files changed", String.valueOf(files.isArray() ? files.size() : 0));
				sb.append("</table>");
			}
		}

		// --- Comparison to the default branch -------------------------------
		if (!defaultBranch.isBlank() && !defaultBranch.equals(branch) && !isCancelled(cancelled)) {
			progress.accept("Comparing with " + defaultBranch + "…");
			JsonNode cmp = ghJson("repos/" + repo + "/compare/" + defaultBranch + "..." + branch, cancelled);
			if (cmp != null && !cmp.path("status").isMissingNode()) {
				sb.append(section("Compared with " + esc(defaultBranch)));
				sb.append("<table>");
				row(sb, "Status", text(cmp, "status"));
				row(sb, "Ahead by", cmp.path("ahead_by").asInt(0) + " commit(s)");
				row(sb, "Behind by", cmp.path("behind_by").asInt(0) + " commit(s)");
				row(sb, "Commits in range", String.valueOf(cmp.path("total_commits").asInt(0)));
				sb.append("</table>");
			}
		}

		// --- Branch protection ----------------------------------------------
		JsonNode protection = branchNode.path("protection");
		if (branchNode.path("protected").asBoolean(false) && protection.isObject()) {
			sb.append(section("Protection"));
			sb.append("<table>");
			row(sb, "Enabled", yesNo(protection.path("enabled").asBoolean(false)));
			JsonNode checks = protection.path("required_status_checks");
			if (checks.isObject()) {
				rowOptional(sb, "Enforcement", text(checks, "enforcement_level"));
				JsonNode contexts = checks.path("contexts");
				if (contexts.isArray() && contexts.size() > 0) {
					List<String> names = new ArrayList<>();
					contexts.forEach(c -> names.add(c.asText()));
					row(sb, "Required checks", String.join(", ", names));
				}
			}
			sb.append("</table>");
		}

		// --- Pull requests for this branch ----------------------------------
		if (!isCancelled(cancelled)) {
			progress.accept("Loading pull requests…");
			String owner = repo.contains("/") ? repo.substring(0, repo.indexOf('/')) : "";
			JsonNode prs = ghJson(
					"repos/" + repo + "/pulls?state=all&per_page=20&head=" + owner + ":" + branch, cancelled);
			if (prs != null && prs.isArray() && prs.size() > 0) {
				sb.append(section("Pull requests (" + prs.size() + ")"));
				sb.append("<table>");
				for (JsonNode pr : prs) {
					String label = esc(text(pr, "title"))
							+ " <span class='dim'>(" + esc(text(pr, "state")) + ")</span>";
					String prUrl = text(pr, "html_url");
					String key = "#" + pr.path("number").asInt();
					rowRaw(sb, prUrl.isBlank() ? key : "<a href='" + esc(prUrl) + "'>" + key + "</a>", label);
				}
				sb.append("</table>");
			}
		}

		sb.append(htmlFoot());
		return sb.toString();
	}

	// -------------------------------------------------------------- gh calls ---

	/** Run {@code gh api <endpoint>} and parse the JSON, or {@code null} on any failure. */
	private JsonNode ghJson(String endpoint, AtomicBoolean cancelled) {
		if (isCancelled(cancelled)) {
			return null;
		}
		try {
			String out = Gh.run(List.of("api", endpoint), cancelled);
			return out.isBlank() ? null : MAPPER.readTree(out);
		} catch (Exception e) {
			if (!isCancelled(cancelled)) {
				log.warn("gh api {} failed: {}", endpoint, e.getMessage());
			}
			return null;
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
				+ ".dim{color:" + dim + ";}"
				+ "pre.msg{white-space:pre-wrap;background:" + toHex(blend(fg, bg, 0.9f))
				+ ";padding:8px;margin:4px 0;font-family:Consolas,monospace;font-size:11px;}"
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

	private String errorHtml(String repo, String branch, String message) {
		return htmlHead()
				+ "<h1>" + esc(branch) + "</h1>"
				+ "<div class='sub'>" + esc(repo) + "</div>"
				+ "<h2>Unable to load branch information</h2>"
				+ "<p>" + esc(message == null ? "Unknown error." : message) + "</p>"
				+ "<p class='dim'>Ensure the GitHub CLI (gh) is installed and authenticated.</p>"
				+ htmlFoot();
	}

	// --------------------------------------------------------------- utility ---

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

	private static String firstLine(String s) {
		if (s == null) {
			return "";
		}
		int nl = s.indexOf('\n');
		return nl < 0 ? s.strip() : s.substring(0, nl).strip();
	}

	private static String shortSha(String sha) {
		return sha != null && sha.length() >= 7 ? sha.substring(0, 7) : sha;
	}

	private static String joinNameEmail(String name, String email) {
		if (name.isBlank() && email.isBlank()) {
			return "";
		}
		if (email.isBlank()) {
			return name;
		}
		return name.isBlank() ? email : name + " <" + email + ">";
	}

	private static String yesNo(boolean b) {
		return b ? "Yes" : "No";
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
		return "GitHub Branch: " + (currentResource != null ? currentResource.getName() : "");
	}

	// ------------------------------------------------------------- metadata ---

	@Override
	public String uuid() {
		return PluginId;
	}



}
