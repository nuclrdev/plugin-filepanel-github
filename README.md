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

`GithubFilePanelProvider` implements `FilePanelNuclrPlugin`. All data fetching goes through the `gh/` layer, which shells out to `gh repo list`, `gh api /repos/{owner}/{repo}/branches`, and `gh api /repos/{owner}/{repo}/git/trees/{sha}`. Responses are parsed via Jackson. `QuickViewRepoPlugin` and `QuickViewBranchPlugin` provide inline quick-view panels for the repo root and branch level respectively. `GitHubClone` resolves the opposite panel's local directory and runs `gh repo clone` for the selected branch there.

## 🗂️ Source layout

```text
src/main/java/dev/nuclr/plugin/core/panel/github/
├── GithubFilePanelProvider.java   plugin entry point, navigation routing
├── QuickViewRepoPlugin.java       quick-view provider for repository info
├── QuickViewBranchPlugin.java     quick-view provider for branch details
├── ResourcesHelper.java           resource tagging and path utilities
├── gh/
│   ├── Gh.java                    CLI runner wrapper
│   ├── GitHubRepos.java           repository listing
│   ├── GitHubBranches.java        branch listing
│   ├── GitHubSourceListing.java   source directory listing
│   ├── GitHubClone.java           F5 branch clone into the opposite panel
│   ├── GithubSource.java          source tree model
│   └── BranchSource.java          branch tree model
└── model/
    ├── RootResource.java
    ├── RepoResource.java
    ├── BranchResource.java
    ├── SourceResource.java
    ├── SourceNode.java
    └── TreeEntry.java
```

## 📚 Dependencies

| Library | Version | Purpose |
|---|---|---|
| `dev.nuclr:platform-sdk` | `3.0.1` | Nuclr platform interfaces |
| `jackson-databind` | `2.21.1` | JSON parsing of `gh` CLI output |
| `commons-io` | `2.22.0` | File utility helpers |
| `commons-lang3` | `3.20.0` | String utilities |

## 📜 License

Apache License 2.0 — see [LICENSE](LICENSE).
