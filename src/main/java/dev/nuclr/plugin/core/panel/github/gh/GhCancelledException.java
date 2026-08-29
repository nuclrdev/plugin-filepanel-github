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

import java.io.IOException;

/**
 * Raised when a GitHub CLI command is abandoned because the caller's
 * cancellation flag was set.
 *
 * <p>
 * This is an expected outcome — the user navigated away or pressed Escape — not
 * a failure, so callers should report it quietly rather than logging it as an
 * error. It extends {@link IOException} so that it still travels the existing
 * {@code throws IOException} signatures unchanged.
 */
public class GhCancelledException extends IOException {

	private static final long serialVersionUID = 1L;

	public GhCancelledException(String message) {
		super(message);
	}
}
