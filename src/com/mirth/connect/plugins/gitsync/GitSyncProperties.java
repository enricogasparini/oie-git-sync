/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

/**
 * Keys used for the plugin's persisted properties (OIE extension properties).
 *
 * <p>Centralising these constants lets the server-side plugin, the Swing settings panel, and any
 * tests refer to the same identifiers without drifting — a typo in any one location is otherwise a
 * silent bug that only surfaces at runtime.
 */
public final class GitSyncProperties {

  private GitSyncProperties() {}

  // Lifecycle / general
  public static final String ENABLED = "enabled";
  public static final String ENVIRONMENT_NAME = "environmentName";
  public static final String NODE_ROLE = "nodeRole";

  // Repository
  public static final String REPO_PATH = "repoPath";
  public static final String REMOTE_URL = "remoteUrl";
  public static final String REMOTE_NAME = "remoteName";
  public static final String BRANCH = "branch";
  public static final String PUSH_ENABLED = "pushEnabled";
  public static final String PUSH_RETRY_COUNT = "pushRetryCount";
  public static final String PUSH_RETRY_DELAY_MS = "pushRetryDelayMs";

  // Credentials
  public static final String CREDENTIAL_TYPE = "credentialType";
  public static final String CREDENTIAL_USERNAME = "credentialUsername";
  public static final String CREDENTIAL_PASSWORD = "credentialPassword";

  // Commit identity / layout
  public static final String COMMIT_AUTHOR_NAME = "commitAuthorName";
  public static final String COMMIT_AUTHOR_EMAIL = "commitAuthorEmail";
  public static final String COMMIT_BRANCH_PATTERN = "commitBranchPattern";

  // Scope toggles
  public static final String SYNC_CHANNELS = "syncChannels";
  public static final String SYNC_CODE_TEMPLATES = "syncCodeTemplates";
  public static final String SYNC_GLOBAL_SCRIPTS = "syncGlobalScripts";
  public static final String SYNC_CHANNEL_GROUPS = "syncChannelGroups";

  // Drift detection
  public static final String DRIFT_BRANCH_PATTERN = "driftBranchPattern";

  // Security
  public static final String API_KEY = "apiKey";

  // Internal state
  public static final String LAST_PROMOTED_COMMIT = "lastPromotedCommit";

  // Defaults
  public static final String DEFAULT_REPO_PATH = "appdata/git-sync-repo";
  public static final String DEFAULT_REMOTE_NAME = "origin";
  public static final String DEFAULT_BRANCH = "main";
  public static final String DEFAULT_ENVIRONMENT = "dev";
  public static final String DEFAULT_COMMIT_BRANCH_PATTERN = "gitsync/{username}/{date}";
  public static final String DEFAULT_AUTHOR_NAME = "OIE Git Sync";
  public static final String DEFAULT_AUTHOR_EMAIL = "gitsync@oie.local";
  public static final int DEFAULT_PUSH_RETRY_COUNT = 3;
  public static final long DEFAULT_PUSH_RETRY_DELAY_MS = 2000L;
}
