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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.gh.GitHubArtifacts;

/** A GitHub Actions artifact exposed to Commander as a downloadable ZIP file. */
public final class ArtifactResource extends NuclrResource {

	private static final long serialVersionUID = -7983258010131782704L;

	public static final String Marker = "github-actions-artifact";
	public static final String Repo = "github-artifact-repo";
	public static final String ArtifactId = "github-artifact-id";
	public static final String ArtifactName = "github-artifact-name";
	public static final String Expired = "github-artifact-expired";

	public static final String NameColumn = "Artifact";
	public static final String RunColumn = "Run";
	public static final String BranchColumn = "Branch";
	public static final String SizeColumn = "Size";
	public static final String CreatedColumn = "Created";
	public static final String ExpiresColumn = "Expires";
	public static final String StatusColumn = "Status";

	public ArtifactResource(String repo, GitHubArtifacts.Artifact artifact) {
		// A virtual file must remain path-less so viewers use openInputStream() rather
		// than attempting to open its routing tag on the local filesystem.
		super(null);
		String archiveName = archiveName(artifact.getName());
		setName(archiveName);
		setFolder(false);
		setReadable(!artifact.isExpired());
		setLength(artifact.getSizeInBytes());
		setCreatedDateTime(artifact.getCreatedAt());
		setLastModifiedDateTime(artifact.getUpdatedAt());
		setUuid("gh://repo/" + repo + "/actions/artifacts/" + artifact.getId());

		getMetadata().put(Marker, true);
		getMetadata().put(Repo, repo);
		getMetadata().put(ArtifactId, artifact.getId());
		getMetadata().put(ArtifactName, artifact.getName());
		getMetadata().put(Expired, artifact.isExpired());
		getMetadata().put(NameColumn, archiveName);
		getMetadata().put(RunColumn, artifact.getRunId() > 0 ? "#" + artifact.getRunId() : "");
		getMetadata().put(BranchColumn, artifact.getBranch());
		getMetadata().put(SizeColumn, humanSize(artifact.getSizeInBytes()));
		getMetadata().put(CreatedColumn, artifact.getCreatedAt());
		getMetadata().put(ExpiresColumn, artifact.getExpiresAt());
		getMetadata().put(StatusColumn, artifact.isExpired() ? "Expired" : "Available");
	}

	public static boolean isArtifact(NuclrResource resource) {
		return resource != null && resource.getMetadata(Marker, false);
	}

	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		if (getMetadata(Expired, false)) {
			throw new IOException("Artifact has expired: " + getMetadata(ArtifactName, getName()));
		}
		String repo = getMetadata(Repo, "");
		long id = getMetadata(ArtifactId, 0L);
		Path archive = Files.createTempFile("nuclr-gh-artifact-", ".zip");
		try {
			GitHubArtifacts.download(repo, id, archive, null);
			InputStream input = Files.newInputStream(archive);
			return new FilterInputStream(input) {
				@Override
				public void close() throws IOException {
					try {
						super.close();
					} finally {
						Files.deleteIfExists(archive);
					}
				}
			};
		} catch (Exception e) {
			Files.deleteIfExists(archive);
			throw e;
		}
	}

	private static String archiveName(String name) {
		String normalized = name == null || name.isBlank() ? "artifact" : name.strip();
		return normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
				? normalized : normalized + ".zip";
	}

	private static String humanSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		String units = "KMGTPE";
		int exp = Math.min((int) (Math.log(bytes) / Math.log(1024)), units.length());
		return String.format("%.1f %sB", bytes / Math.pow(1024, exp), units.charAt(exp - 1));
	}
}
