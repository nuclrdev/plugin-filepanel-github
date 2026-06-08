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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in an in-memory snapshot of a repository branch's source tree.
 *
 * <p>
 * A directory holds its children keyed by leaf name; a file holds its raw bytes
 * in {@link #content}. The whole tree for a branch is built once (from the
 * branch zipball) and cached, so subsequent browsing never hits the network.
 */
public final class SourceNode {

	/** Path relative to the repository root, e.g. {@code "src/main/Foo.java"}. "" for the root. */
	private final String path;

	/** Leaf name, e.g. {@code "Foo.java"}. "" for the root. */
	private final String name;

	private final boolean directory;

	/** File bytes; {@code null} for directories. */
	private byte[] content;

	/** Child nodes keyed by leaf name (insertion order; sorted on listing). */
	private final Map<String, SourceNode> children = new LinkedHashMap<>();

	public SourceNode(String path, String name, boolean directory) {
		this.path = path;
		this.name = name;
		this.directory = directory;
	}

	public String getPath() {
		return path;
	}

	public String getName() {
		return name;
	}

	public boolean isDirectory() {
		return directory;
	}

	public byte[] getContent() {
		return content;
	}

	public void setContent(byte[] content) {
		this.content = content;
	}

	public long getSize() {
		return content == null ? 0L : content.length;
	}

	public Collection<SourceNode> getChildren() {
		return children.values();
	}

	/**
	 * Insert {@code node} as a child of this directory, locating intermediate
	 * directories by its relative path and creating any that are missing.
	 */
	public void put(String relativePath, SourceNode node) {
		SourceNode parent = ensureParent(relativePath);
		parent.children.put(node.getName(), node);
	}

	/** Ensure (creating if needed) the directory at {@code relativePath} exists, returning it. */
	public SourceNode ensureDirectory(String relativePath) {
		if (relativePath.isEmpty()) {
			return this;
		}
		SourceNode parent = ensureParent(relativePath);
		String leaf = leafOf(relativePath);
		return parent.children.computeIfAbsent(leaf, n -> new SourceNode(relativePath, n, true));
	}

	private SourceNode ensureParent(String relativePath) {
		int slash = relativePath.lastIndexOf('/');
		if (slash < 0) {
			return this;
		}
		String parentPath = relativePath.substring(0, slash);
		SourceNode current = this;
		StringBuilder acc = new StringBuilder();
		for (String segment : parentPath.split("/")) {
			if (acc.length() > 0) {
				acc.append('/');
			}
			acc.append(segment);
			final String soFar = acc.toString();
			current = current.children.computeIfAbsent(segment, n -> new SourceNode(soFar, n, true));
		}
		return current;
	}

	/** Resolve a descendant by its repo-relative path, or {@code null} if absent. */
	public SourceNode find(String relativePath) {
		if (relativePath == null || relativePath.isEmpty()) {
			return this;
		}
		SourceNode current = this;
		for (String segment : relativePath.split("/")) {
			current = current.children.get(segment);
			if (current == null) {
				return null;
			}
		}
		return current;
	}

	/** Children sorted directories-first, then alphabetically (case-insensitive). */
	public List<SourceNode> sortedChildren() {
		List<SourceNode> sorted = new ArrayList<>(children.values());
		sorted.sort((a, b) -> {
			if (a.directory != b.directory) {
				return a.directory ? -1 : 1;
			}
			return a.name.compareToIgnoreCase(b.name);
		});
		return sorted;
	}

	private static String leafOf(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}
}
