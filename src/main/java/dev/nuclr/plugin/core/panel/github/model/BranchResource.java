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

import java.nio.file.Path;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.gh.GitHubBranches;

public class BranchResource extends NuclrResource {

	private static final long serialVersionUID = 6406294149034958472L;

	/** Path tag identifying a branch resource to the host's supports() probe. */
	public static final String Tag = "github-repo-branch";

	/** Metadata key holding the owner/repo this branch belongs to. */
	public static final String Repo = "github-branch-repo";
	
	public static final String Branch = "github-branch";

	public BranchResource(String repo, String branch) {

		super(Path.of(Tag));

		this.setName(branch);
		this.setFolder(true);

		var uuid = "gh://repo/" + repo + "/branch/" + branch;
		this.setUuid(uuid);

		this.getMetadata().put(GitHubBranches.BranchName, branch);
		this.getMetadata().put(Repo, repo);
		this.getMetadata().put(Branch, true);
	}

}
