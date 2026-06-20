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
import dev.nuclr.plugin.core.panel.github.gh.GitHubRepos;
import dev.nuclr.plugin.core.panel.github.gh.GitHubRepos.Repository;

public class RepoResource extends NuclrResource {

	private static final long serialVersionUID = -7911032571859257618L;

	public static String Repository = "Repository";

	public RepoResource(Repository repo) {

		super(Path.of("github-repo"));

		this.getMetadata().put("github-repo", true);
		this.setFolder(true);

		setName(repo.getDisplayName());

		var uuid = "gh://repo/" + repo;
		this.setUuid(uuid);

		this.getMetadata().put(GitHubRepos.Visibility, repo.getVisibility());
		this.getMetadata().put(Repository, repo);

	}

	@Override
	public void setName(String name) {
		super.setName(name);
		this.getMetadata().put(GitHubRepos.RepositoryName, name);
	}

	public RepoResource(NuclrResource r) {
		this(r.getMetadata(Repository, new Repository()));
	}

}
