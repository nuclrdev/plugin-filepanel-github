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
package dev.nuclr.plugin.core.panel.github.model;

import java.io.InputStream;
import java.nio.file.OpenOption;
import java.nio.file.Path;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.gh.BranchSource;

/**
 * A single entry (directory or file) inside a branch's cached source tree.
 *
 * <p>
 * A directory carries a path tag so the host's {@code supports(path)} probe can recognise it as
 * navigable. A file carries no path at all: there is no local file behind it, and claiming one
 * would only mislead the quick-view plugins, which read a path-less resource through
 * {@link #openInputStream} but try to open a non-null path directly. File content is served
 * lazily from the in-memory {@link BranchSource} cache.
 */
public final class SourceResource extends NuclrResource {

	private static final long serialVersionUID = 8924130471122508913L;

	/** Path tag for a navigable source directory. */
	public static final String DirTag = "github-source-dir";

	/** Metadata keys carrying enough context to resolve the node from the cache. */
	public static final String Repo = "github-source-repo";
	public static final String Branch = "github-source-branch";
	public static final String SourcePath = "github-source-path";
	public static final String Source = "github-source";

	/** Display column keys used by the source listing. */
	public static final String NameColumn = "Name";
	public static final String SizeColumn = "Size";

	public SourceResource(String repo, String branch, SourceNode node) {

		super(node.isDirectory() ? Path.of(DirTag) : null);

		setName(node.getName());
		setFolder(node.isDirectory());
		setLength(node.getSize());

		setUuid("gh://repo/" + repo + "/branch/" + branch + "/src/" + node.getPath());

		getMetadata().put(Repo, repo);
		getMetadata().put(Branch, branch);
		getMetadata().put(SourcePath, node.getPath());
		getMetadata().put(Source, true);
		getMetadata().put(NameColumn, node.getName());
		getMetadata().put(SizeColumn, node.isDirectory() ? "<DIR>" : humanSize(node.getSize()));
	}

	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		if (isFolder()) {
			throw new UnsupportedOperationException("Cannot read content of a directory: " + getName());
		}
		String repo = getMetadata(Repo, "");
		String branch = getMetadata(Branch, "");
		String path = getMetadata(SourcePath, "");
		// Content is read lazily from the spooled branch archive rather than held in
		// memory, so large repositories don't exhaust the heap.
		InputStream content = BranchSource.openFile(repo, branch, path);
		if (content == null) {
			throw new java.io.IOException("Source not cached for " + repo + "@" + branch + ":" + path);
		}
		return content;
	}

	private static String humanSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		String units = "KMGTPE";
		int exp = (int) (Math.log(bytes) / Math.log(1024));
		if (exp > units.length()) {
			exp = units.length();
		}
		double value = bytes / Math.pow(1024, exp);
		return String.format("%.1f %sB", value, units.charAt(exp - 1));
	}
}
