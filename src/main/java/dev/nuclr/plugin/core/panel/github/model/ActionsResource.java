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

/** Navigable repository node containing its GitHub Actions artifacts. */
public final class ActionsResource extends NuclrResource {

	private static final long serialVersionUID = 7152401627461644087L;

	public static final String Tag = "github-actions";
	public static final String Repo = "github-actions-repo";
	public static final String NameColumn = "Name";

	public ActionsResource(String repo) {
		super(Path.of(Tag));
		setName("Actions");
		setFolder(true);
		setUuid("gh://repo/" + repo + "/actions");
		getMetadata().put(Repo, repo);
		getMetadata().put(NameColumn, "Actions");
	}
}
