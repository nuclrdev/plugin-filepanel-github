# 🐙 GitHub File Panel

An official [Nuclr Commander](https://nuclr.dev) plugin that adds a **GitHub** root to the file panel, powered by the [GitHub CLI](https://cli.github.com/) (`gh`). Navigate your repositories, browse branches, inspect source trees, and press **F5 Clone** on a branch to clone it into a writable local file panel on the other side.

## ✨ What it shows

| Navigation level | Content |
|---|---|
| 📁 `GitHub/Repositories/` | All repositories accessible to your authenticated account |
| 📁 `<owner∕repo>/` | Branch list for the repository |
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

- **Browsing is read-only.** The single write operation is **F5 Clone**, offered on a branch node: it clones that branch into the directory currently shown by the *other* panel. The clone runs off the UI thread, so Commander stays responsive.
- Navigation is tag-based: resources carry `github-repo`, branch, and source-dir tags so the provider can route list and quick-view calls correctly.
- The plugin lazily checks for `gh` availability on `init()` and disables itself gracefully if the CLI is missing.

## 📥 Installation

Copy the signed plugin archive and detached signature into the Nuclr Commander `plugins/` directory:

```text
filepanel-github-<version>.zip
filepanel-github-<version>.zip.sig
```

Nuclr Commander verifies the RSA-SHA256 signature against `nuclr-cert.pem` on load. The plugin becomes available immediately without a restart.

## ⚙️ How it works

`GithubFilePanelProvider` implements `FilePanelNuclrPlugin`. All data fetching goes through the `gh/` layer, and every text-producing command runs through `Gh.run`, a shared runner that keeps `gh`'s diagnostics off stdout, applies a timeout, and honours the panel's cancellation flag. Repository discovery uses the paginated `/user/repos` API with owner, collaborator, and organization-member affiliations; branch browsing uses the branches API; and source trees come from a lazily read, temporary branch zipball. Responses are parsed via Jackson. `QuickViewRepoPlugin` and `QuickViewBranchPlugin` provide inline quick-view panels for the repo root and branch level respectively. `GitHubClone` resolves the opposite panel's local directory and runs `gh repo clone` for the selected branch there, respecting the Git protocol configured in GitHub CLI.

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
│   ├── GitHubSourceListing.java   source directory listing
│   ├── GitHubClone.java           F5 branch clone into the opposite panel
│   └── BranchSource.java          cached branch archive and source tree
└── model/
    ├── RootResource.java
    ├── RepoResource.java
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
