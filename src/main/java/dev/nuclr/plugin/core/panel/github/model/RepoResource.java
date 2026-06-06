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

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.gh.GitHubRepos;
import dev.nuclr.plugin.core.panel.github.gh.GitHubRepos.Repository;

public class RepoResource extends NuclrResource {

	public RepoResource(Repository repo) {

		super(null);

		this.getMetadata().put("github-repo", true);
		this.setName(repo.getDisplayName());

		var uuid = "gh://repo/" + repo;
		this.setUuid(uuid);

		this.getMetadata().put(GitHubRepos.Repository, repo.getDisplayName());
		this.getMetadata().put(GitHubRepos.Visibility, repo.getVisibility());

	}

}
