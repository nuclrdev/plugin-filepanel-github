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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.core.panel.github.model.TreeEntry;

public class GithubSource {

	
	public static List<TreeEntry> listTree(String repo, String ref) throws IOException, InterruptedException {
		
	    Process process = new ProcessBuilder(
	            "gh", "api",
	            "repos/" + repo + "/git/trees/" + ref + "?recursive=1")
	            .redirectErrorStream(true)
	            .start();

	    String json;
	    
	    try (var reader = new BufferedReader(
	            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
	        json = reader.lines().collect(Collectors.joining("\n"));
	    }

	    int exit = process.waitFor();
	    if (exit != 0) {
	        throw new IOException("gh exited with code " + exit + ": " + json);
	    }

	    List<TreeEntry> entries = new ArrayList<>();
	    JsonNode root = new ObjectMapper().readTree(json);

	    if (root.path("truncated").asBoolean(false)) {
	        throw new IOException("Tree truncated — repo too large for a single recursive call");
	    }

	    for (JsonNode node : root.path("tree")) {
	        entries.add(new TreeEntry(
	                node.path("path").asText(),
	                node.path("type").asText(),
	                node.path("sha").asText(),
	                node.has("size") ? node.get("size").asLong() : null));
	    }
	    return entries;
	}
	
	public static TreeEntry buildHierarchy(List<TreeEntry> flat) {
		
	    TreeEntry root = new TreeEntry("", "tree", null, null);

	    Map<String, TreeEntry> byPath = new HashMap<>();
	    byPath.put("", root);
	    for (TreeEntry e : flat) {
	        byPath.put(e.getPath(), e);
	    }

	    for (TreeEntry e : flat) {
	        String path = e.getPath();
	        int i = path.lastIndexOf('/');
	        String parentPath = i < 0 ? "" : path.substring(0, i);
	        TreeEntry parent = byPath.get(parentPath);
	        if (parent == null) {
	            parent = root; // defensive: parent dir missing from list
	        }
	        parent.getChildren().add(e);
	    }
	    return root;
	}
	
}
