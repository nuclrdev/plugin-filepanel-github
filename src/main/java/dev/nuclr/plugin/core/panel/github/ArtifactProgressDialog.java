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
package dev.nuclr.plugin.core.panel.github;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginCallback;

/**
 * Modeless progress window for a batch of artifact operations: the current item, a
 * determinate bar over the whole batch, and a Cancel button. It is also the
 * {@link NuclrPluginCallback} handed to the operation, forwarding to the host's callback
 * when there is one — the host passes none for Delete, so without this window a batch
 * ran with no visible progress at all.
 *
 * <p>Cancel raises {@link #cancelledFlag()}, which gh polls, so the request in flight is
 * killed rather than waited out. Callback methods may be called from any thread.
 */
final class ArtifactProgressDialog implements NuclrPluginCallback {

	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final NuclrPluginCallback host;
	private final String doneVerb;
	private final JDialog dialog;
	private final JLabel itemLabel = new JLabel("Preparing…");
	private final JLabel countLabel = new JLabel(" ");
	private final JProgressBar bar = new JProgressBar();
	private final JButton cancelButton = new JButton("Cancel");

	private ArtifactProgressDialog(String title, String doneVerb, int total, NuclrPluginCallback host) {
		this.host = host;
		this.doneVerb = doneVerb;

		dialog = new JDialog(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(), title);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				cancel();
			}
		});

		bar.setMinimum(0);
		bar.setMaximum(Math.max(total, 1));
		bar.setStringPainted(true);
		countLabel.setText("0 of " + total + " " + doneVerb);
		cancelButton.addActionListener(e -> cancel());

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));

		JPanel center = new JPanel(new BorderLayout(0, 4));
		center.add(itemLabel, BorderLayout.NORTH);
		center.add(bar, BorderLayout.CENTER);
		center.add(countLabel, BorderLayout.SOUTH);
		content.add(center, BorderLayout.CENTER);

		JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		south.add(cancelButton);
		content.add(south, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.pack();
		dialog.setSize(new Dimension(Math.max(420, dialog.getWidth()), dialog.getHeight()));
		dialog.setLocationRelativeTo(dialog.getOwner());
	}

	/** Build and show the window. Must be called on the EDT. */
	static ArtifactProgressDialog show(String title, String doneVerb, int total, NuclrPluginCallback host) {
		ArtifactProgressDialog progress = new ArtifactProgressDialog(title, doneVerb, total, host);
		progress.dialog.setVisible(true);
		return progress;
	}

	/** The flag to pass down to gh so Cancel also stops the request in flight. */
	AtomicBoolean cancelledFlag() {
		return cancelled;
	}

	void close() {
		SwingUtilities.invokeLater(dialog::dispose);
	}

	private void cancel() {
		cancelled.set(true);
		cancelButton.setEnabled(false);
		itemLabel.setText("Cancelling…");
	}

	@Override
	public void onStart(String description) {
		SwingUtilities.invokeLater(() -> {
			if (!cancelled.get()) {
				itemLabel.setText(description == null ? " " : description);
			}
		});
		if (host != null) {
			host.onStart(description);
		}
	}

	@Override
	public void onProgress(long current, long total) {
		SwingUtilities.invokeLater(() -> {
			if (total > 0) {
				bar.setMaximum((int) total);
				bar.setValue((int) Math.min(current, total));
				countLabel.setText(current + " of " + total + " " + doneVerb);
			}
		});
		if (host != null) {
			host.onProgress(current, total);
		}
	}

	@Override
	public void onComplete() {
		if (host != null) {
			host.onComplete();
		}
	}

	@Override
	public void onError(String description, Exception e) {
		if (host != null) {
			host.onError(description, e);
		}
	}

	@Override
	public boolean isCancelled() {
		if (!cancelled.get() && host != null && host.isCancelled()) {
			cancelled.set(true);
		}
		return cancelled.get();
	}
}
