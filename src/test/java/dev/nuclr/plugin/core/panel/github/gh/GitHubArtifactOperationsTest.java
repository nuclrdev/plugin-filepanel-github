package dev.nuclr.plugin.core.panel.github.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.github.model.ArtifactResource;

class GitHubArtifactOperationsTest {

	@Test
	void sanitizesArtifactNamesForTheLocalFilesystem() {
		assertEquals("windows_build_1.zip", GitHubArtifactOperations.safeFileName("windows:build/1"));
		assertEquals("already.zip", GitHubArtifactOperations.safeFileName("already.zip"));
		assertEquals("artifact.zip", GitHubArtifactOperations.safeFileName("..."));
	}

	@Test
	void deletesArtifactsOneAtATimeReportingProgressAfterEach() throws Exception {
		var callback = new RecordingCallback();
		var deletedIds = new ArrayList<Long>();

		int deleted = GitHubArtifactOperations.delete(artifacts(1, 2, 3), callback, new AtomicBoolean(false),
				(repo, id, cancelled) -> deletedIds.add(id));

		assertEquals(3, deleted);
		assertEquals(List.of(1L, 2L, 3L), deletedIds);
		assertEquals(List.of("0/3", "1/3", "1/3", "2/3", "2/3", "3/3"), callback.progress);
		assertEquals(3, callback.completed);
	}

	@Test
	void cancellingStopsBeforeTheNextArtifactAndReachesTheRequestInFlight() throws Exception {
		var cancel = new AtomicBoolean(false);
		var deletedIds = new ArrayList<Long>();

		int deleted = GitHubArtifactOperations.delete(artifacts(1, 2, 3), new RecordingCallback(), cancel,
				(repo, id, cancelled) -> {
					assertSame(cancel, cancelled);
					deletedIds.add(id);
					cancel.set(true);
				});

		assertEquals(1, deleted);
		assertEquals(List.of(1L), deletedIds);
	}

	private static List<NuclrResource> artifacts(long... ids) {
		var result = new ArrayList<NuclrResource>();
		for (long id : ids) {
			var artifact = new GitHubArtifacts.Artifact();
			artifact.setId(id);
			artifact.setName("build-" + id);
			result.add(new ArtifactResource("nuclr/app", artifact));
		}
		return result;
	}

	private static final class RecordingCallback implements NuclrPluginCallback {
		final List<String> progress = new ArrayList<>();
		int completed;

		@Override
		public void onStart(String description) {
		}

		@Override
		public void onProgress(long current, long total) {
			progress.add(current + "/" + total);
		}

		@Override
		public void onComplete() {
			completed++;
		}

		@Override
		public void onError(String description, Exception e) {
		}

		@Override
		public boolean isCancelled() {
			return false;
		}
	}
}
