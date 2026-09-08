# 🐙 GitHub File Panel

An official [Nuclr Commander](https://nuclr.dev) plugin that adds a **GitHub** root to the file panel, powered by the [GitHub CLI](https://cli.github.com/) (`gh`). Navigate repositories, browse branches and source trees, inspect GitHub Actions artifacts, and copy or move artifact ZIPs into a writable local file panel.

## ✨ What it shows

| Navigation level | Content |
|---|---|
| 📁 `GitHub/Repositories/` | All repositories accessible to your authenticated account |
| 📁 `<owner∕repo>/` | An Actions folder and the repository's branches |
| 📁 `<owner∕repo>/Actions/` | Actions artifacts with run, branch, size, creation, expiry, and availability details |
| 📁 `<branch>/` | Top-level directory listing of that branch |
| 📄 Files | Source files and subdirectories |
| 👁️ Quick View | Repository info panel and branch-level quick view |

> 💡 The `owner∕repo` node uses a division-slash (∕) display character because `/` is not valid in Windows filesystem path components.

## ✅ Prerequisites

The GitHub CLI must be installed and authenticated:

```bash
gh auth login
```

## 🧭 Design notes

- **F5 Copy** downloads selected Actions artifacts as ZIPs to the writable local directory shown in the other panel. **F6 Move** downloads each ZIP before deleting that artifact from GitHub. **F8 Delete** permanently removes selected artifacts after confirmation. Copy, move, and delete run off the UI thread and support cancellation.
- **F5 Clone** on a branch clones that branch into the directory currently shown by the other panel.
- Navigation is tag-based: resources carry repository, Actions, branch, and source-directory tags so the provider can route list and quick-view calls correctly.
- The plugin lazily checks for `gh` availability on `init()` and disables itself gracefully if the CLI is missing.

## 📥 Installation

Copy the signed plugin archive and detached signature into the Nuclr Commander `plugins/` directory:

```text
filepanel-github-<version>.zip
filepanel-github-<version>.zip.sig
```

Nuclr Commander verifies the RSA-SHA256 signature against `nuclr-cert.pem` on load. The plugin becomes available immediately without a restart.

## ⚙️ How it works

`GithubFilePanelProvider` implements `FilePanelNuclrPlugin`. All data fetching goes through the `gh/` layer, and every text-producing command runs through `Gh.run`, a shared runner that keeps `gh`'s diagnostics off stdout, applies a timeout, and honours the panel's cancellation flag. Repository discovery uses the paginated `/user/repos` API with owner, collaborator, and organization-member affiliations; branch browsing uses the branches API; Actions browsing uses the paginated repository-artifacts API; and source trees come from a lazily read, temporary branch zipball. Artifact downloads stream directly to temporary files before being atomically published in the destination panel. Responses are parsed via Jackson. `QuickViewRepoPlugin` and `QuickViewBranchPlugin` provide inline quick-view panels for the repo root and branch level respectively. `GitHubClone` resolves the opposite panel's local directory and runs `gh repo clone` for the selected branch there, respecting the Git protocol configured in GitHub CLI.

## 🗂️ Source layout

```text
src/main/java/dev/nuclr/plugin/core/panel/github/
├── GithubFilePanelProvider.java   plugin entry point, navigation routing
├── QuickViewRepoPlugin.java       quick-view provider for repository info
├── QuickViewBranchPlugin.java     quick-view provider for branch details
├── ResourcesHelper.java           resource tagging and path utilities
├── gh/
│   ├── Gh.java                    shared cancellable/timed CLI runner
│   ├── GitHubRepos.java           repository listing
│   ├── GitHubBranches.java        branch listing
│   ├── GitHubArtifacts.java       Actions artifact listing, download, and deletion
│   ├── GitHubArtifactOperations.java  F5/F6/F8 artifact operations
│   ├── GitHubSourceListing.java   source directory listing
│   ├── GitHubClone.java           F5 branch clone into the opposite panel
│   └── BranchSource.java          cached branch archive and source tree
└── model/
    ├── RootResource.java
    ├── RepoResource.java
    ├── ActionsResource.java
    ├── ArtifactResource.java
    ├── BranchResource.java
    ├── SourceResource.java
    └── SourceNode.java
```

## 📚 Dependencies

| Library | Version | Purpose |
|---|---|---|
| `dev.nuclr:platform-sdk` | `4.0.0` | Nuclr platform interfaces |
| `jackson-databind` | `3.2.1` | JSON parsing of `gh` CLI output |
| `slf4j-api` | `2.0.17` | Logging API supplied by Commander |
| `junit-jupiter` | `5.11.4` | Unit tests (test scope only) |

## 📜 License

Apache License 2.0 — see [LICENSE](LICENSE).
