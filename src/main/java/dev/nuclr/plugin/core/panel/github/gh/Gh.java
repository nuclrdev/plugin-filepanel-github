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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Shared, cancellable runner for text-producing GitHub CLI commands. */
public final class Gh {

	private static final long POLL_INTERVAL_MILLIS = 100;
	private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);

	private Gh() {
	}

	public static boolean isGhInstalled() {
		return succeeds(List.of("--version"));
	}

	public static boolean isGhAuthenticated() {
		return succeeds(List.of("auth", "status"));
	}

	private static boolean succeeds(List<String> args) {
		try {
			run(args, null, CHECK_TIMEOUT);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (IOException e) {
			return false;
		}
	}

	public static String run(List<String> args, AtomicBoolean cancelled)
			throws IOException, InterruptedException {
		return run(args, cancelled, COMMAND_TIMEOUT);
	}

	static String run(List<String> args, AtomicBoolean cancelled, Duration timeout)
			throws IOException, InterruptedException {
		if (cancelled != null && cancelled.get()) {
			throw new GhCancelledException("GitHub CLI command was cancelled");
		}

		List<String> command = new ArrayList<>(args.size() + 1);
		command.add("gh");
		command.addAll(args);

		Process process = new ProcessBuilder(command).start();
		// gh never reads stdin here; closing it stops a prompting gh from waiting out
		// the whole timeout instead of failing fast.
		process.getOutputStream().close();

		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		AtomicReference<IOException> stdoutFailure = new AtomicReference<>();
		AtomicReference<IOException> stderrFailure = new AtomicReference<>();

		Thread stdoutPump = pump(process.getInputStream(), stdout, stdoutFailure, "gh-stdout");
		Thread stderrPump = pump(process.getErrorStream(), stderr, stderrFailure, "gh-stderr");
		long deadline = System.nanoTime() + timeout.toNanos();
		boolean completed = false;

		try {
			while (!process.waitFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)) {
				if (cancelled != null && cancelled.get()) {
					throw new GhCancelledException("GitHub CLI command was cancelled");
				}
				if (System.nanoTime() >= deadline) {
					throw new IOException("GitHub CLI command timed out after " + timeout.toSeconds() + " seconds");
				}
			}
			completed = true;
		} finally {
			// Cleanup must not mask the cancellation or timeout that brought us here, so
			// an interrupt raised while tearing down is re-flagged instead of thrown.
			if (!completed) {
				terminate(process);
			}
			joinQuietly(stdoutPump);
			joinQuietly(stderrPump);
		}

		if (stdoutFailure.get() != null) {
			throw stdoutFailure.get();
		}
		if (stderrFailure.get() != null) {
			throw stderrFailure.get();
		}

		String output = stdout.toString(StandardCharsets.UTF_8);
		if (process.exitValue() != 0) {
			String diagnostic = stderr.toString(StandardCharsets.UTF_8).strip();
			throw new IOException("gh " + String.join(" ", args) + " exited " + process.exitValue()
					+ (diagnostic.isBlank() ? "" : ": " + diagnostic));
		}
		return output;
	}

	private static Thread pump(
			InputStream input,
			ByteArrayOutputStream output,
			AtomicReference<IOException> failure,
			String name) {
		Thread thread = new Thread(() -> {
			try (input; output) {
				input.transferTo(output);
			} catch (IOException e) {
				failure.set(e);
			}
		}, name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/** Stop a process that was cancelled or timed out, escalating if it lingers. */
	static void terminate(Process process) {
		process.destroy();
		try {
			if (!process.waitFor(1, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	private static void joinQuietly(Thread thread) {
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
