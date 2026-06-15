# Security Policy

## Supported versions

Security fixes are made on `main` and shipped as new tagged releases. Older
tags do not receive back-ports.

| Version | Supported          |
|---------|--------------------|
| 1.0.x   | :white_check_mark: |

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.
Instead, report them privately via GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
feature on this repository.

We take security issues seriously and will investigate reports on a
best-effort basis as soon as we're able. After triage we'll coordinate a
fix and a disclosure timeline with the reporter.

## Deployment hardening

`oie-git-sync` is a bidirectional sync between an Open Integration Engine
(OIE) server and a Git remote. OIE is commonly used in regulated healthcare
environments, so operators should treat the plugin, the local repo clone,
and the remote credential as sensitive components.

When deploying in production:

- **Least-privilege Git credentials.** Use a scoped Personal Access Token
  with `repo` write access only on the target repository. Rotate regularly.
- **HTTPS with tokens, not passwords.** Prefer `HTTPS_TOKEN` authentication.
  Basic auth with a password is supported for legacy servers only.
- **Node role hygiene.** Production OIE instances should be configured as
  `RECEIVER` (or `BOTH` with `pushEnabled=false` if you need audit commits
  from prod to a drift branch). This prevents manual edits on prod from
  being pushed to the main trunk branch.
- **Separate environments.** Use distinct trunk branches or distinct
  repositories per OIE environment. Do not promote the same commit
  automatically to multiple environments without a review gate.
- **Review gates.** Make promotion from Git to prod a manual or
  pipeline-triggered step that requires a merged, approved PR on the trunk
  branch. Never auto-promote directly from a feature branch.
- **Logging.** Treat OIE server logs as sensitive. The plugin sanitises
  remote URLs before logging (no embedded tokens) but commit messages,
  channel names, and usernames are still recorded. Use `INFO` or higher in
  production; `DEBUG` may be noisier.
- **Local clone permissions.** The local Git clone directory (default
  `appdata/git-sync-repo`) may contain channel configurations in plain XML.
  Restrict filesystem access to the OIE service account.
- **Config map template.** The plugin deliberately never commits
  configuration map *values* to Git — only keys and comments. Do not work
  around this; store secrets in a dedicated secret store.

## What the plugin already does

- **Credentials encrypted at rest.** The Git credential password/token is
  encrypted via OIE's `Encryptor` before being persisted to the plugin
  properties database. Tagged values are not re-encrypted on re-save, and
  the plaintext is never round-tripped back to the admin console.
- **URL sanitisation.** Remote URLs with embedded credentials (of the form
  `https://user:token@host/repo.git`) are stripped of userinfo before being
  written to log messages.
- **SyncGuard against circular sync.** Promoting channels from Git to OIE
  is wrapped in a thread-local suppression flag so the resulting save hooks
  do not commit the same change back to Git.
- **Config map values excluded.** Only keys and comments are serialised to
  `config-map-template.json`. Values (credentials, environment secrets)
  never reach Git.
- **Optional API key on the promotion endpoints.** In addition to OIE RBAC
  (the `Promote` extension permission), an optional `apiKey` plugin property
  (encrypted at rest) requires callers of `/promote` and `/promote/preview`
  to present a matching `X-GitSync-API-Key` header; mismatches return 403.
  Empty means disabled (RBAC alone).
- **Prod drift detection.** When the `driftBranchPattern` property is set,
  manual edits on the node are committed to a dedicated drift branch (e.g.
  `prod-drift/{date}`) instead of a user feature branch, giving an audit
  trail for out-of-band changes on production.
- **No force push, no auto-rebase.** The plugin refuses to rewrite history
  on the remote. Stale branches after a squash-merge workflow are handled
  by recreating the local feature branch from the current base tip, not by
  force-pushing.
- **Dedicated feature branches.** User commits go to `gitsync/{username}/{date}`
  branches (pattern configurable), not directly to the trunk branch, so a
  review gate is always in the path from editor to production.
- **MPL-2.0 licence audit trail.** Every source file carries an SPDX header.

## JAR signing

Release ZIPs contain plain (unsigned) JARs. OIE's admin console is launched
via OpenWebStart with `<all-permissions/>`, which requires every JAR on the
console classpath to be signed — for this plugin that is `gitsync-shared.jar`
and `gitsync-client.jar`. Against a signed OIE release, sign those two JARs
with your organisation's code-signing certificate (or a key your OpenWebStart
deployments trust) before installing. Contributors testing against a
self-built OIE can use the automated dev-keystore flow in
`scripts/dev-setup.sh` — see `docs/local-testing.md`; that flow is for local
development only and must not be used in production.

## Known limitations

- **SSH key management.** The `SSH_KEY` credential type uses the system's
  default SSH identity or an agent. There is no in-plugin key storage.
- **SSH host key verification.** SSH transport (JGit + JSch) verifies host
  keys against the OIE service account's `~/.ssh/known_hosts`. Pre-populate
  the remote's host key there before first use; do not disable host key
  checking.
