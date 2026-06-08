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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dev.nuclr.plugin.core.panel.github.model.SourceNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches and caches the full source tree of a repository branch.
 *
 * <p>
 * The first time a branch is opened its entire source is downloaded once via
 * {@code gh api repos/{repo}/zipball/{branch}} (a single request that GitHub
 * follows to a codeload archive), expanded fully into an in-memory
 * {@link SourceNode} tree, and cached keyed by {@code repo@branch}. All later
 * browsing of that branch — directories and file contents alike — is served
 * from the cache without further network calls.
 */
@Slf4j
public final class BranchSource {

	private static final ConcurrentMap<String, SourceNode> cache = new ConcurrentHashMap<>();

	private BranchSource() {
	}

	private static String key(String repo, String branch) {
		return repo + "@" + branch;
	}

	/** Return the cached root for the branch, or {@code null} if not yet fetched. */
	public static SourceNode peek(String repo, String branch) {
		return cache.get(key(repo, branch));
	}

	/**
	 * Return the cached source-tree root for the branch, fetching and caching it on
	 * first access. Concurrent callers for the same branch fetch at most once.
	 */
	public static SourceNode getOrFetch(String repo, String branch) throws IOException, InterruptedException {
		SourceNode cached = cache.get(key(repo, branch));
		if (cached != null) {
			return cached;
		}
		// computeIfAbsent can't propagate checked exceptions, so fetch outside it and
		// only publish on success; a redundant concurrent fetch is harmless.
		SourceNode fetched = fetch(repo, branch);
		SourceNode existing = cache.putIfAbsent(key(repo, branch), fetched);
		return existing != null ? existing : fetched;
	}

	/** Drop a cached branch (e.g. to force a re-fetch on next open). */
	public static void invalidate(String repo, String branch) {
		cache.remove(key(repo, branch));
	}

	private static SourceNode fetch(String repo, String branch) throws IOException, InterruptedException {

		log.info("Fetching source zipball for {}@{}", repo, branch);

		Process process = new ProcessBuilder("gh", "api", "repos/" + repo + "/zipball/" + branch).start();

		// Drain stderr on a separate thread so a chatty gh can't deadlock the binary
		// stdout stream we're reading the archive from.
		StringBuilder stderr = new StringBuilder();
		Thread stderrPump = new Thread(() -> drain(process.getErrorStream(), stderr), "gh-zipball-stderr");
		stderrPump.setDaemon(true);
		stderrPump.start();

		// Fully drain stdout into memory before parsing. ZipInputStream stops at the
		// central-directory signature and never consumes the trailing central
		// directory / EOCD bytes; closing the pipe with those still unread makes gh
		// fail its stdout write ("The pipe has been ended" on Windows) and exit 1.
		// Reading the whole archive first lets gh finish writing cleanly.
		byte[] archive = readFully(new BufferedInputStream(process.getInputStream()));

		int exit = process.waitFor();
		stderrPump.join();

		if (exit != 0) {
			throw new IOException("gh exited with code " + exit + " fetching " + repo + "@" + branch + ": " + stderr);
		}

		SourceNode root = new SourceNode("", "", true);

		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {

				// Strip GitHub's top-level "owner-repo-<sha>/" wrapper directory.
				String name = entry.getName();
				int slash = name.indexOf('/');
				String relative = slash < 0 ? "" : name.substring(slash + 1);
				if (relative.isEmpty()) {
					continue;
				}

				if (entry.isDirectory() || relative.endsWith("/")) {
					String dirPath = trimTrailingSlash(relative);
					if (!dirPath.isEmpty()) {
						root.ensureDirectory(dirPath);
					}
					continue;
				}

				byte[] content = readEntry(zip);
				SourceNode file = new SourceNode(relative, leafOf(relative), false);
				file.setContent(content);
				root.put(relative, file);
			}
		}

		return root;
	}

	private static byte[] readFully(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	private static byte[] readEntry(ZipInputStream zip) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = zip.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
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

	private static String trimTrailingSlash(String path) {
		return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
	}

	private static String leafOf(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}
}
