# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] - 2026-06-12

### Security
- **Log injection hardening (CodeQL).** REST-supplied values (usernames,
  branch names, commit messages, author identity) are passed through a new
  `LogSanitiser.clean()` helper that strips line terminators before being
  interpolated into log messages, so a crafted value cannot forge extra log
  lines.

### Fixed
- Malformed numeric plugin properties (`pushRetryCount`, `pushRetryDelayMs`)
  no longer throw `NumberFormatException` during configuration updates — they
  fall back to the documented defaults with a warning.
- Added missing `@Override` annotations on the settings panel's `SwingWorker`
  implementations and removed an unused `runImport` parameter (CodeQL notes).

## [1.0.0] - 2026-06-12

Initial public release.

### Added
- Server extension skeleton implementing `ServicePlugin`, `ChannelPlugin`, and
  `CodeTemplateServerPlugin`.
- **Direction A (OIE → Git), batch commit flow.**
  - `PendingChangeTracker` records per-user pending changes to
    `.gitsync-pending/{username}/` with atomic manifest writes.
  - "Commit to Git" dialog flushes the user's pending changes as a single
    commit on a per-user feature branch (default pattern
    `gitsync/{username}/{date}`; tokens `{username} {date} {environment} {branch}`).
  - Cross-user reconciliation: edits to the same entity by a later user
    remove it from earlier users' pending sets (matches OIE last-save-wins).
  - "Snapshot to Git" exports a full mirror of the current OIE state
    (including deletions since the previous snapshot) to a
    `gitsync/fullsync/{date}` branch.
  - Commit author resolved from the OIE user's first name, last name, and
    email via `UserController`.
- **Direction B (Git → OIE), pipeline-driven promotion.**
  - `POST /api/extensions/gitsync/promote` imports changes from Git.
  - Incremental diff against `lastPromotedCommit`; or `fresh=true` recovery
    mode that re-imports everything from the target commit.
  - Imports **all artefact types**, not just channels:
    - Channels via `ChannelController.updateChannel`, preserving the
      target's channel metadata (enabled state, pruning settings) and tags.
    - Code template libraries via `CodeTemplateController.updateLibrariesAndTemplates`,
      merged with the target's unchanged libraries so a partial promotion
      never deletes them.
    - Channel groups via `ChannelController.updateChannelGroups`, merged the
      same way.
    - Global scripts via `ScriptController.setGlobalScripts` (a script file
      deleted in Git blanks the corresponding script on the target).
    - Configuration map via merge: new keys added with empty values and the
      template's comments; existing values never overwritten.
  - Pinned promotion: `commitHash` in the request resets the working tree to
    that commit so the imported content matches the pin exactly; an
    unresolvable pin fails loudly.
  - The fetch/detect/apply phases run under the repository lock so a
    concurrent "Commit to Git" cannot leak feature-branch content into a
    promotion.
  - `lastPromotedCommit` only advances when every artefact imported cleanly,
    so failed artefacts are retried on the next promotion.
  - With `overwrite=false`, channels skipped because they are locally
    modified on the target are reported as errors and excluded from deploy.
  - Preview-then-apply UX in the admin console with a "Deploy after import"
    checkbox.
  - `SyncGuard` thread-local suppression prevents circular commits during
    promotion.
  - `PERMISSION_PROMOTE` RBAC permission.
- **API key authentication for promotion endpoint.** New `apiKey`
  plugin property (encrypted at rest via `CredentialStore`). When set,
  the servlet validates the `X-GitSync-API-Key` header on `promote()`
  and `previewPromotion()` calls using a constant-time comparison,
  returning 403 on mismatch. Empty means no check. Defence-in-depth for
  CI/CD pipelines.
- **Prod drift detection.** New `driftBranchPattern` plugin property.
  When set, `commitPending()` routes changes to a drift branch (e.g.
  `prod-drift/{date}`) instead of the user's feature branch. Uses the
  same token system as the commit branch pattern. Empty means disabled.
- **Node roles.** `CONTRIBUTOR` / `RECEIVER` / `BOTH` (default `BOTH`). The
  settings panel filters task buttons based on the selected role.
- **Recovery.** `Reset Local Repo` nukes and re-clones the local working
  tree; `Restore from Main` re-imports everything from the trunk branch.
- **Git repository layout** under the repo root:
  - `channels/{channel-id}/channel.xml` + `channel-metadata.json`
  - `code-templates/{library-id}/library.xml` + `{template-id}.xml`
  - `global-scripts/{deploy,undeploy,preprocessor,postprocessor}.js`
  - `channel-groups/{group-id}.xml`
  - `config-map/config-map-template.json` (keys and comments only)
  - `.gitsync-ignore` (channel IDs to exclude)
- **Swing settings panel** with status bar, sync log table, Test Connection,
  Snapshot to Git, Commit to Git, Import from Git, Restore from Main, Reset
  Local Repo.
- **XStream alias registration.** `ObjectXMLSerializer.processAnnotations()`
  in `init()` for all 6 model DTOs so REST XML uses short element names
  instead of fully-qualified class names.
- **Test suite, coverage gate and formatter.** 250+ unit and integration
  tests across the server module covering `FileUtils`, `CredentialStore`,
  `ArtifactSerializer`, `PendingChangeTracker`, `GitRepoManager`
  (JGit-backed lifecycle, recovery, deletion staging, pinned reset, and a
  16-thread setter/commit contention test), `PromotionService` merge
  helpers, `GitSyncPlugin` lifecycle and save-hook guards,
  `NodeRole`/`CredentialType`/`GitSyncProperties`, model DTOs, and an
  annotation contract test for `GitSyncServletInterface`. Ant targets
  `test`, `coverage`, `coverage-enforce`, `format`, and `format-check`.
  JaCoCo enforces 80% line / 70% branch on the in-scope set. google-java-format
  runs as a CI gate. Mockito uses the inline mock maker via the SPI
  resource so `final` methods and `mockStatic` work without surprises.
- **Distribution** as a single `gitsync-<version>.zip` containing
  `gitsync-server.jar`, `gitsync-shared.jar`, `gitsync-client.jar`, and
  runtime dependencies (JGit, JSch, SLF4J API).

### Fixed
- **Pre-release review fixes (Direction B / promotion):**
  - Promoting a subset of channel groups or code template libraries no
    longer deletes the unchanged ones on the target — the engine's update
    methods treat the passed collection as the complete set, so the changed
    artefacts are now merged with the existing ones first.
  - Code template bodies are now actually imported: they are passed in the
    `updatedCodeTemplates` parameter, which is the only place the engine
    persists template content from. Templates deleted in Git are removed on
    the target.
  - Promotion no longer re-enables disabled channels, resets pruning
    settings, or strips channel tags — the target's existing metadata is
    carried onto the imported channel before `updateChannel`.
  - `lastPromotedCommit` is only persisted when the promotion succeeded in
    full; previously a partial failure advanced it and the failed artefacts
    were silently never retried.
  - Promotion reads the working tree under the repository lock, closing a
    race where an in-flight "Commit to Git" could check out a feature branch
    mid-promotion and unreviewed content would be imported.
  - A `PromotionRequest.commitHash` pin now imports that commit's file
    content (previously the diff was pinned but the content was read from
    HEAD).
  - `updateChannel`'s return value is checked: skipped channels
    (`overwrite=false` against a locally modified target) are recorded as
    errors and excluded from deployment instead of being reported as
    promoted and redeployed with the old configuration.
- **Pre-release review fixes (Direction A / commit flow):**
  - A channel save landing while "Commit to Git" is in flight is no longer
    silently wiped: the post-commit clear removes only the snapshot of
    changes that was actually committed.
  - A failed commit (push exhaustion, I/O error mid-flight) no longer
    wedges every subsequent commit — the base-branch checkout discards the
    stranded dirty working tree first.
  - Channel/library deletions are always recorded as pending DELETEs;
    checking the base-branch working tree missed artefacts committed to a
    not-yet-merged feature branch, and Git resurrected them after merge.
  - "Snapshot to Git" now mirrors deletions: each synced directory is
    cleared and re-serialised, and the resulting deletions are staged
    (JGit's `AddCommand` does not stage deletions on its own).
- **`SyncGuard.runSuppressed` no longer drops outer suppression on
  nested unwind.** A nested call previously cleared the thread-local
  on exit even when the outer scope had been suppressed, briefly
  re-enabling Direction A commits inside Direction B promotion. Both
  paths run on the same Jetty thread so the bug was real but quiet;
  surfaced by the unit tests, fixed in the same commit.

### Security
- **Credentials encrypted at rest.** The Git credential password/token is
  encrypted via OIE's `Encryptor` before being persisted to plugin
  properties. Save is idempotent: re-saving the form without editing the
  password field does not touch the stored value.
- **No credential round-trip to the client.** The admin console's password
  field is never populated from stored properties; a tooltip explains that
  leaving the field blank preserves the existing value.
- **Remote URL sanitisation.** HTTPS URLs with embedded userinfo (`user:token@`)
  are stripped of credentials before being written to log messages.
- **Path traversal hardening.** Usernames consisting only of dots (`.`,
  `..`) are neutralised before being used as pending-directory names (a
  username of `..` previously made `POST /pending/discard` resolve to the
  repo root and recursively delete the local clone), and the resolved
  directory is verified to sit inside `.gitsync-pending/`. Artefact IDs are
  validated against path separators and dot-only segments at every site
  where they become directory names — save hooks, pending-change commits,
  and promotion change detection.
- **Constant-time API key comparison.** The `X-GitSync-API-Key` check uses
  `MessageDigest.isEqual` so response timing cannot leak how much of a
  guessed key matched.
