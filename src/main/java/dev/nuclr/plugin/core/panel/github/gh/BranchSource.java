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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import dev.nuclr.plugin.core.panel.github.model.SourceNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches and caches the source tree of a repository branch.
 *
 * <p>
 * The first time a branch is opened its archive is downloaded once via
 * {@code gh api repos/{repo}/zipball/{branch}} (a single request that GitHub
 * follows to a codeload archive) and <em>spooled to a temp file</em>. The
 * archive is scanned to build a metadata-only {@link SourceNode} tree (paths,
 * names and sizes — but <em>not</em> file bytes), which is cached keyed by
 * {@code repo@branch} alongside the spooled archive.
 *
 * <p>
 * All later browsing of that branch is served from the cached tree without
 * further network calls, and individual file contents are read lazily from the
 * spooled archive on demand (see {@link #openFile}). Neither the full archive
 * nor decompressed file contents are held in the heap, so even very large
 * repositories can be browsed without exhausting memory.
 */
@Slf4j
public final class BranchSource {

	/** Cached branch: its metadata tree plus the on-disk archive backing lazy reads. */
	private record Branch(SourceNode root, Path archive) {
	}

	private static final ConcurrentMap<String, Branch> cache = new ConcurrentHashMap<>();

	private BranchSource() {
	}

	private static String key(String repo, String branch) {
		return repo + "@" + branch;
	}

	/** Return the cached root for the branch, or {@code null} if not yet fetched. */
	public static SourceNode peek(String repo, String branch) {
		Branch b = cache.get(key(repo, branch));
		return b == null ? null : b.root();
	}

	/**
	 * Return the cached source-tree root for the branch, fetching and caching it on
	 * first access. Concurrent callers for the same branch fetch at most once.
	 */
	public static SourceNode getOrFetch(String repo, String branch) throws IOException, InterruptedException {
		Branch cached = cache.get(key(repo, branch));
		if (cached != null) {
			return cached.root();
		}
		// computeIfAbsent can't propagate checked exceptions, so fetch outside it and
		// only publish on success. A redundant concurrent fetch is harmless, but its
		// spooled archive must be deleted so it doesn't leak on disk.
		Branch fetched = fetch(repo, branch);
		Branch existing = cache.putIfAbsent(key(repo, branch), fetched);
		if (existing != null) {
			deleteQuietly(fetched.archive());
			return existing.root();
		}
		return fetched.root();
	}

	/**
	 * Open an input stream over a single file within a cached branch, read lazily
	 * from the spooled archive. Returns {@code null} if the branch is not cached or
	 * the path does not resolve to a file entry.
	 */
	public static InputStream openFile(String repo, String branch, String path) throws IOException {
		Branch b = cache.get(key(repo, branch));
		if (b == null) {
			return null;
		}
		SourceNode node = b.root().find(path);
		if (node == null || node.isDirectory() || node.getEntryName() == null) {
			return null;
		}

		ZipFile zip = new ZipFile(b.archive().toFile());
		ZipEntry entry = zip.getEntry(node.getEntryName());
		if (entry == null) {
			zip.close();
			return null;
		}
		// Close the ZipFile once the entry stream is closed by the caller.
		InputStream raw = zip.getInputStream(entry);
		return new java.io.FilterInputStream(raw) {
			@Override
			public void close() throws IOException {
				try {
					super.close();
				} finally {
					zip.close();
				}
			}
		};
	}

	/** Drop a cached branch and delete its spooled archive (e.g. to force a re-fetch). */
	public static void invalidate(String repo, String branch) {
		Branch b = cache.remove(key(repo, branch));
		if (b != null) {
			deleteQuietly(b.archive());
		}
	}

	private static Branch fetch(String repo, String branch) throws IOException, InterruptedException {

		log.info("Fetching source zipball for {}@{}", repo, branch);

		Process process = new ProcessBuilder("gh", "api", "repos/" + repo + "/zipball/" + branch).start();

		// Drain stderr on a separate thread so a chatty gh can't deadlock the binary
		// stdout stream we're reading the archive from.
		StringBuilder stderr = new StringBuilder();
		Thread stderrPump = new Thread(() -> drain(process.getErrorStream(), stderr), "gh-zipball-stderr");
		stderrPump.setDaemon(true);
		stderrPump.start();

		// Spool the whole archive to a temp file before parsing. ZipInputStream stops
		// at the central-directory signature and never consumes the trailing central
		// directory / EOCD bytes; closing the pipe with those still unread makes gh
		// fail its stdout write ("The pipe has been ended" on Windows) and exit 1.
		// Draining stdout fully to disk (rather than into the heap) lets gh finish
		// cleanly without materialising a multi-hundred-MB byte[].
		Path archive = Files.createTempFile("nuclr-gh-zipball-", ".zip");
		archive.toFile().deleteOnExit();
		try {
			try (InputStream in = process.getInputStream()) {
				Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
			}

			int exit = process.waitFor();
			stderrPump.join();

			if (exit != 0) {
				throw new IOException(
						"gh exited with code " + exit + " fetching " + repo + "@" + branch + ": " + stderr);
			}

			SourceNode root = buildTree(archive);
			return new Branch(root, archive);
		} catch (IOException | InterruptedException | RuntimeException e) {
			deleteQuietly(archive);
			throw e;
		}
	}

	/** Scan the spooled archive once to build the metadata-only source tree. */
	private static SourceNode buildTree(Path archive) throws IOException {

		SourceNode root = new SourceNode("", "", true);

		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
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

				SourceNode file = new SourceNode(relative, leafOf(relative), false);
				file.setEntryName(name);
				file.setSize(sizeOf(entry, zip));
				root.put(relative, file);
			}
		}

		return root;
	}

	/**
	 * Determine an entry's uncompressed size. The zipball's local headers usually
	 * carry it directly; when they don't ({@code -1}), the bytes are skipped (not
	 * buffered) so the count costs no heap.
	 */
	private static long sizeOf(ZipEntry entry, ZipInputStream zip) throws IOException {
		long declared = entry.getSize();
		if (declared >= 0) {
			return declared;
		}
		long total = 0;
		byte[] buffer = new byte[8192];
		int read;
		while ((read = zip.read(buffer)) != -1) {
			total += read;
		}
		return total;
	}

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			log.warn("Failed to delete spooled archive {}: {}", path, e.getMessage());
		}
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
