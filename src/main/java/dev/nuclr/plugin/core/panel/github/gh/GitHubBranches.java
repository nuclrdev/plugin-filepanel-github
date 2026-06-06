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
import java.util.List;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.NuclrResourceData;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.ResourcesHelper;
import dev.nuclr.plugin.core.panel.github.model.BranchResource;
import dev.nuclr.plugin.core.panel.github.model.RepoResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class GitHubBranches {

	public static final String BranchName = "Branches";

	public static NuclrResourceData branches(NuclrResource repository) {

		var data = new NuclrResourceData();
		
		// Clone repository metadata to avoid modifying the original resource
		var up = ResourcesHelper.root();
		up.setName("..");
		up.getMetadata().put(BranchName, "..");
		data.getEntries().add(up);

		data.setColumnNames(List.of(BranchName));
		
		var repo = repository.getMetadata(GitHubRepos.RepositoryName, "");

		try {
			for (var branch : listBranches(repo)) {

				log.info("Found branch: {}", branch);

				var r = new BranchResource(repo, branch);

				data.getEntries().add(r);
			}
		} catch (Exception e) {
			log.error("Failed to list branches for repo {}: {}", repo, e.getMessage());
		}

		return data;
	}

	private static List<String> listBranches(String repo) throws Exception {

		Process process = new ProcessBuilder("gh", "api", "repos/" + repo + "/branches", "--paginate", "--jq",
				".[].name").redirectErrorStream(true).start();

		List<String> branches = new ArrayList<>();

		try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					branches.add(line.strip());
				}
			}
		}

		int exit = process.waitFor();

		if (exit != 0) {
			throw new IOException("gh exited with code " + exit + ": " + String.join("\n", branches));
		}

		return branches;

	}
}
