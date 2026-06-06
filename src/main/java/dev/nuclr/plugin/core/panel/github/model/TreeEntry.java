package dev.nuclr.plugin.core.panel.github.model;

import java.util.ArrayList;
import java.util.List;

public final class TreeEntry {
	private final String path;
	private final String type; // "blob" or "tree"
	private final String sha;
	private final Long size;
	private final List<TreeEntry> children = new ArrayList<>();

	public TreeEntry(String path, String type, String sha, Long size) {
		this.path = path;
		this.type = type;
		this.sha = sha;
		this.size = size;
	}

	public String getPath() {
		return path;
	}

	public String getType() {
		return type;
	}

	public String getSha() {
		return sha;
	}

	public Long getSize() {
		return size;
	}

	public List<TreeEntry> getChildren() {
		return children;
	}

	public String getName() {
		int i = path.lastIndexOf('/');
		return i < 0 ? path : path.substring(i + 1);
	}
}