package dev.nuclr.plugin.core.panel.github;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.panel.FilePanelProvider;
import dev.nuclr.plugin.panel.PanelRoot;

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
public class GithubFilePanelProvider implements FilePanelProvider {

	private static final char DISPLAY_SLASH = '\u2215';
	private static final String GH_MISSING_MESSAGE =
			"GitHub CLI (gh) is not available or not authenticated.\n"
			+ "Run: gh auth login";
	private static final long REFRESH_INTERVAL_MS = 60_000L;

	private final ObjectMapper mapper = new ObjectMapper();
	private final Path mountBase;
	private final Path githubRoot;
	private final Path repositoriesRoot;
	private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
	private volatile long lastRefreshEpochMs = 0L;

	public GithubFilePanelProvider() {
		this.mountBase = Path.of(System.getProperty("java.io.tmpdir"), "nuclr", "github-filepanel-v1");
		this.githubRoot = mountBase.resolve("GitHub");
		this.repositoriesRoot = githubRoot.resolve("Repositories");
	}

	@Override
	public String id() {
		return "github-repositories";
	}

	@Override
	public String displayName() {
		return "GitHub";
	}

	@Override
	public int priority() {
		return 30;
	}

	@Override
	public List<PanelRoot> roots() {
		try {
			ensureScaffold();
		} catch (Exception e) {
			try {
				Files.createDirectories(repositoriesRoot);
				writeText(repositoriesRoot.resolve("ERROR.txt"), "GitHub provider error:\n" + e.getMessage());
			} catch (IOException ignored) {
			}
		}
		triggerRefresh(false);
		return List.of(new PanelRoot("GitHub", githubRoot));
	}

	@Override
	public boolean supportsPath(Path path) {
		return path != null && path.normalize().startsWith(githubRoot.normalize());
	}

	private void refreshMaterializedTree() throws IOException {
		ensureScaffold();
		clearDirectory(repositoriesRoot);

		List<RepoSummary> repositories;
		try {
			repositories = listRepositories();
		} catch (IOException e) {
			writeText(repositoriesRoot.resolve("ERROR.txt"), GH_MISSING_MESSAGE + "\n\n" + e.getMessage());
			return;
		}

		if (repositories.isEmpty()) {
			writeText(repositoriesRoot.resolve("EMPTY.txt"), "No repositories returned by gh repo list.");
			return;
		}

		for (RepoSummary repo : repositories) {
			materializeRepositoryNode(repo);
		}
	}

	private void ensureScaffold() throws IOException {
		Files.createDirectories(repositoriesRoot);
	}

	private void triggerRefresh(boolean force) {
		long now = System.currentTimeMillis();
		if (!force && now - lastRefreshEpochMs < REFRESH_INTERVAL_MS) {
			return;
		}
		if (!refreshInProgress.compareAndSet(false, true)) {
			return;
		}

		Thread.ofVirtual().start(() -> {
			try {
				refreshMaterializedTree();
			} catch (Exception ignored) {
				// roots() must stay fast and resilient; errors are materialized in ERROR.txt.
			} finally {
				lastRefreshEpochMs = System.currentTimeMillis();
				refreshInProgress.set(false);
			}
		});
	}

	private List<RepoSummary> listRepositories() throws IOException {
		String json = runGh(List.of(
				"repo",
				"list",
				"--limit",
				"1000",
				"--json",
				"name,nameWithOwner,owner,description,isPrivate,isFork,url"));

		JsonNode root = mapper.readTree(json);
		var repos = new ArrayList<RepoSummary>();
		if (!root.isArray()) {
			return repos;
		}
		for (JsonNode n : root) {
			String nameWithOwner = text(n, "nameWithOwner");
			if (nameWithOwner.isBlank()) {
				continue;
			}
			repos.add(new RepoSummary(
					nameWithOwner,
					text(n.path("owner"), "login"),
					text(n, "name"),
					textOrNull(n, "description"),
					n.path("isPrivate").asBoolean(false),
					n.path("isFork").asBoolean(false),
					text(n, "url")));
		}
		return repos;
	}

	private void materializeRepositoryNode(RepoSummary repo) throws IOException {
		String displayName = repo.nameWithOwner().replace('/', DISPLAY_SLASH);
		Path repoPath = repositoriesRoot.resolve(displayName);
		Path infoPath = repoPath.resolve("Info");
		Files.createDirectories(infoPath);

		String infoText;
		try {
			infoText = buildRepositoryInfoText(repo.nameWithOwner());
		} catch (IOException e) {
			infoText = fallbackInfoText(repo) + "\n\nInfo fetch failed:\n" + e.getMessage();
		}
		writeText(infoPath.resolve("README.txt"), infoText);
	}

	private String buildRepositoryInfoText(String nameWithOwner) throws IOException {
		String json = runGh(List.of(
				"repo",
				"view",
				nameWithOwner,
				"--json",
				"name,nameWithOwner,owner,description,isPrivate,isFork,defaultBranchRef,url,homepage,visibility"));

		JsonNode n = mapper.readTree(json);
		String owner = text(n.path("owner"), "login");
		String name = text(n, "name");
		String description = textOrNull(n, "description");
		String visibility = text(n, "visibility").toUpperCase(Locale.ROOT);
		String defaultBranch = text(n.path("defaultBranchRef"), "name");
		String homepage = textOrNull(n, "homepage");
		String url = text(n, "url");

		return String.join("\n",
				"Repository: " + text(n, "nameWithOwner"),
				"Owner: " + owner,
				"Name: " + name,
				"Description: " + (description == null ? "" : description),
				"Visibility: " + visibility,
				"Private: " + n.path("isPrivate").asBoolean(false),
				"Fork: " + n.path("isFork").asBoolean(false),
				"Default branch: " + defaultBranch,
				"Homepage: " + (homepage == null ? "" : homepage),
				"URL: " + url,
				"",
				"Fetched via gh at " + Instant.now());
	}

	private static String fallbackInfoText(RepoSummary repo) {
		return String.join("\n",
				"Repository: " + repo.nameWithOwner(),
				"Owner: " + repo.owner(),
				"Name: " + repo.name(),
				"Description: " + (repo.description() == null ? "" : repo.description()),
				"Private: " + repo.isPrivate(),
				"Fork: " + repo.isFork(),
				"URL: " + repo.url());
	}

	private String runGh(List<String> args) throws IOException {
		var command = new ArrayList<String>();
		command.add("gh");
		command.addAll(args);

		Process process;
		try {
			process = new ProcessBuilder(command).start();
		} catch (IOException e) {
			throw new IOException("Cannot start gh command: " + String.join(" ", command), e);
		}

		String out;
		String err;
		try {
			out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IOException("Cannot read gh output for: " + String.join(" ", command), e);
		}

		try {
			int code = process.waitFor();
			if (code != 0) {
				String details = !err.isBlank() ? err.strip() : out.strip();
				throw new IOException("gh exited with code " + code + ": " + details);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while running gh", e);
		}

		return out;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? "" : value.asText("");
	}

	private static String textOrNull(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asText("");
		return text.isBlank() ? null : text;
	}

	private static void writeText(Path path, String text) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, text, StandardCharsets.UTF_8, CREATE, WRITE, TRUNCATE_EXISTING);
	}

	private static void clearDirectory(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var stream = Files.list(dir)) {
			for (Path child : stream.toList()) {
				deleteRecursively(child);
			}
		}
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (Files.isDirectory(path)) {
			Files.walkFileTree(path, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}
				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
			return;
		}
		Files.deleteIfExists(path);
	}

	private record RepoSummary(
			String nameWithOwner,
			String owner,
			String name,
			String description,
			boolean isPrivate,
			boolean isFork,
			String url) {
	}
}
