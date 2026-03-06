# filepanel-github

Nuclr Commander GitHub filepanel plugin (v1, repositories only).

This plugin uses GitHub CLI (`gh`) and assumes the user is already authenticated:

`gh auth login`

Current navigation model:

`GitHub/Repositories/<owner∕repo>/Info/README.txt`

Notes:

- This v1 is intentionally minimal and read-only.
- It proves plugin mount, gh integration, repository listing, and repository info view.
- The `owner∕repo` node uses a division-slash display character because `/` is not valid in local filesystem entry names on Windows.
