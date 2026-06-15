/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static com.mirth.connect.client.core.api.servlets.ExtensionServletInterface.OPERATION_PLUGIN_PROPERTIES_GET;
import static com.mirth.connect.client.core.api.servlets.ExtensionServletInterface.OPERATION_PLUGIN_PROPERTIES_SET;
import static com.mirth.connect.plugins.gitsync.GitSyncServletInterface.PERMISSION_MANAGE;
import static com.mirth.connect.plugins.gitsync.GitSyncServletInterface.PERMISSION_VIEW;
import static com.mirth.connect.plugins.gitsync.GitSyncServletInterface.PLUGIN_POINT;

import com.mirth.connect.client.core.TaskConstants;
import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.model.User;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.ChannelPlugin;
import com.mirth.connect.plugins.CodeTemplateServerPlugin;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.plugins.gitsync.model.GitSyncStatus;
import com.mirth.connect.plugins.gitsync.model.PendingChange;
import com.mirth.connect.plugins.gitsync.model.PendingChangeList;
import com.mirth.connect.plugins.gitsync.model.PromotionRequest;
import com.mirth.connect.plugins.gitsync.model.PromotionResult;
import com.mirth.connect.plugins.gitsync.model.SyncRecord;
import com.mirth.connect.plugins.gitsync.model.SyncRecord.Action;
import com.mirth.connect.plugins.gitsync.model.SyncRecord.ArtifactType;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.CodeTemplateController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.ScriptController;
import com.mirth.connect.server.controllers.UserController;
import com.mirth.connect.util.ConfigurationProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main plugin class implementing ServicePlugin (lifecycle/configuration), ChannelPlugin
 * (save/remove/deploy hooks), and CodeTemplateServerPlugin (code template save/remove hooks).
 *
 * <p>Direction A (OIE-to-Git): On save, serialises configs and commits to Git. Direction B
 * (Git-to-OIE): Planned for Phase 3 via the promotion REST endpoint.
 */
public class GitSyncPlugin implements ServicePlugin, ChannelPlugin, CodeTemplateServerPlugin {

  private static final Logger logger = LogManager.getLogger(GitSyncPlugin.class);
  private static final int MAX_LOG_SIZE = 500;

  private static GitSyncPlugin instance;

  // These fields are written by init()/update() and read by save/remove hooks and Jetty request
  // threads, so they are volatile for cross-thread visibility.
  private volatile boolean enabled = false;
  private volatile String environmentName = GitSyncProperties.DEFAULT_ENVIRONMENT;
  private volatile NodeRole nodeRole = NodeRole.BOTH;
  private volatile boolean syncChannels = true;
  private volatile boolean syncCodeTemplates = true;
  private volatile boolean syncGlobalScripts = true;
  private volatile boolean syncChannelGroups = true;

  private volatile GitRepoManager repoManager;
  private volatile ArtifactSerializer serializer;
  private volatile PendingChangeTracker pendingTracker;
  private volatile SyncEventLogger eventLogger;
  private volatile GitSyncStatus status;
  private final LinkedList<SyncRecord> syncLog = new LinkedList<>();

  private volatile Set<String> ignoredChannelIds = Collections.emptySet();

  private volatile Properties currentProperties;

  public GitSyncPlugin() {
    instance = this;
  }

  public static GitSyncPlugin getInstance() {
    return instance;
  }

  /**
   * Returns the decrypted API key for the promotion endpoint, or empty string if not configured.
   * Called by {@link GitSyncServlet} to validate the X-GitSync-API-Key header.
   */
  public String getApiKey() {
    if (currentProperties == null) {
      return "";
    }
    String stored = currentProperties.getProperty(GitSyncProperties.API_KEY, "");
    if (stored.isEmpty()) {
      return "";
    }
    return CredentialStore.decrypt(stored);
  }

  // -----------------------------------------------------------------------
  // ServerPlugin
  // -----------------------------------------------------------------------

  @Override
  public String getPluginPointName() {
    return PLUGIN_POINT;
  }

  // -----------------------------------------------------------------------
  // ServicePlugin
  // -----------------------------------------------------------------------

  @Override
  public void init(Properties properties) {
    currentProperties = properties;
    status = new GitSyncStatus();
    eventLogger = new SyncEventLogger();

    registerXStreamAliases();
    applyProperties(properties);
    logger.info("Git Sync plugin initialised (enabled={})", enabled);
  }

  /**
   * Registers XStream aliases for all model DTOs so the REST API serialises them with short element
   * names (e.g. {@code <promotionRequest>}) instead of fully-qualified class names.
   */
  private void registerXStreamAliases() {
    try {
      ObjectXMLSerializer.getInstance()
          .processAnnotations(
              new Class<?>[] {
                GitSyncStatus.class,
                PendingChange.class,
                PendingChangeList.class,
                PromotionRequest.class,
                PromotionResult.class,
                SyncRecord.class,
              });
    } catch (Exception e) {
      logger.warn("Failed to register XStream aliases — XML may use fully-qualified names", e);
    }
  }

  @Override
  public void start() {
    if (!enabled) {
      logger.info("Git Sync plugin is disabled");
      return;
    }

    try {
      String remoteUrl = currentProperties.getProperty(GitSyncProperties.REMOTE_URL, "");
      repoManager.init(remoteUrl.isBlank() ? null : remoteUrl);
      status.setRepoInitialised(true);
      loadIgnoreFile();
      logger.info("Git Sync plugin started");
    } catch (Exception e) {
      logger.error("Failed to start Git Sync plugin", e);
      status.setRepoInitialised(false);
      status.recordFailure(e.getMessage());
    }
  }

  @Override
  public void stop() {
    if (repoManager != null) {
      repoManager.close();
    }
    logger.info("Git Sync plugin stopped");
  }

  @Override
  public void update(Properties properties) {
    if (repoManager != null) {
      repoManager.close();
    }

    Properties sanitised = applyCredentialSavePolicy(properties);
    currentProperties = sanitised;
    applyProperties(sanitised);

    if (enabled) {
      start();
    }

    // Persist the sanitised (encrypted credential, preserved-on-blank) properties
    // back to OIE so the next read round-trips stable values.
    try {
      ControllerFactory.getFactory()
          .createExtensionController()
          .setPluginProperties(PLUGIN_POINT, sanitised);
    } catch (Exception e) {
      logger.warn("Failed to persist sanitised plugin properties", e);
    }

    logger.info("Git Sync plugin configuration updated (enabled={})", enabled);
  }

  /**
   * Applies the credential-save policy to incoming properties from the client:
   *
   * <ul>
   *   <li>If the incoming password is blank and there is an existing stored value, the existing
   *       value is preserved (blank = "no change", matching typical password-field UX).
   *   <li>If the incoming password is non-blank and not already tagged as encrypted, it is
   *       encrypted via {@link CredentialStore#encrypt(String)} before being written to the
   *       properties that get persisted.
   *   <li>Already-encrypted values round-trip unchanged.
   * </ul>
   *
   * Returns a new {@link Properties} instance; the input is not mutated.
   */
  private Properties applyCredentialSavePolicy(Properties incoming) {
    Properties result = new Properties();
    result.putAll(incoming);

    String incomingPassword = incoming.getProperty(GitSyncProperties.CREDENTIAL_PASSWORD, "");
    String existingPassword =
        currentProperties != null
            ? currentProperties.getProperty(GitSyncProperties.CREDENTIAL_PASSWORD, "")
            : "";

    if (incomingPassword.isEmpty() && !existingPassword.isEmpty()) {
      result.setProperty(GitSyncProperties.CREDENTIAL_PASSWORD, existingPassword);
    } else if (!incomingPassword.isEmpty() && !CredentialStore.isEncrypted(incomingPassword)) {
      result.setProperty(
          GitSyncProperties.CREDENTIAL_PASSWORD, CredentialStore.encrypt(incomingPassword));
    }

    // API key: empty means "clear / disabled" (unlike the Git credential
    // where empty means "keep existing"). This lets admins disable the
    // guard by saving with an empty API key.
    String incomingApiKey = incoming.getProperty(GitSyncProperties.API_KEY, "");
    if (!incomingApiKey.isEmpty() && !CredentialStore.isEncrypted(incomingApiKey)) {
      result.setProperty(GitSyncProperties.API_KEY, CredentialStore.encrypt(incomingApiKey));
    }

    return result;
  }

  @Override
  public Properties getDefaultProperties() {
    Properties defaults = new Properties();
    defaults.setProperty(GitSyncProperties.ENABLED, "false");
    defaults.setProperty(GitSyncProperties.REPO_PATH, GitSyncProperties.DEFAULT_REPO_PATH);
    defaults.setProperty(GitSyncProperties.REMOTE_URL, "");
    defaults.setProperty(GitSyncProperties.REMOTE_NAME, GitSyncProperties.DEFAULT_REMOTE_NAME);
    defaults.setProperty(GitSyncProperties.BRANCH, GitSyncProperties.DEFAULT_BRANCH);
    defaults.setProperty(GitSyncProperties.PUSH_ENABLED, "true");
    defaults.setProperty(
        GitSyncProperties.PUSH_RETRY_COUNT,
        Integer.toString(GitSyncProperties.DEFAULT_PUSH_RETRY_COUNT));
    defaults.setProperty(
        GitSyncProperties.PUSH_RETRY_DELAY_MS,
        Long.toString(GitSyncProperties.DEFAULT_PUSH_RETRY_DELAY_MS));
    defaults.setProperty(GitSyncProperties.CREDENTIAL_TYPE, CredentialType.NONE.name());
    defaults.setProperty(GitSyncProperties.CREDENTIAL_USERNAME, "");
    defaults.setProperty(GitSyncProperties.CREDENTIAL_PASSWORD, "");
    defaults.setProperty(
        GitSyncProperties.COMMIT_AUTHOR_NAME, GitSyncProperties.DEFAULT_AUTHOR_NAME);
    defaults.setProperty(
        GitSyncProperties.COMMIT_AUTHOR_EMAIL, GitSyncProperties.DEFAULT_AUTHOR_EMAIL);
    defaults.setProperty(
        GitSyncProperties.COMMIT_BRANCH_PATTERN, GitSyncProperties.DEFAULT_COMMIT_BRANCH_PATTERN);
    defaults.setProperty(GitSyncProperties.ENVIRONMENT_NAME, GitSyncProperties.DEFAULT_ENVIRONMENT);
    defaults.setProperty(GitSyncProperties.NODE_ROLE, NodeRole.BOTH.name());
    defaults.setProperty(GitSyncProperties.SYNC_CHANNELS, "true");
    defaults.setProperty(GitSyncProperties.SYNC_CODE_TEMPLATES, "true");
    defaults.setProperty(GitSyncProperties.SYNC_GLOBAL_SCRIPTS, "true");
    defaults.setProperty(GitSyncProperties.SYNC_CHANNEL_GROUPS, "true");
    defaults.setProperty(GitSyncProperties.API_KEY, "");
    defaults.setProperty(GitSyncProperties.DRIFT_BRANCH_PATTERN, "");
    return defaults;
  }

  @Override
  public ExtensionPermission[] getExtensionPermissions() {
    ExtensionPermission viewPermission =
        new ExtensionPermission(
            PLUGIN_POINT,
            PERMISSION_VIEW,
            "View Git Sync status and logs.",
            OperationUtil.getOperationNamesForPermission(
                PERMISSION_VIEW, GitSyncServletInterface.class, OPERATION_PLUGIN_PROPERTIES_GET),
            new String[] {TaskConstants.SETTINGS_REFRESH});

    ExtensionPermission managePermission =
        new ExtensionPermission(
            PLUGIN_POINT,
            PERMISSION_MANAGE,
            "Manage Git Sync settings and trigger syncs.",
            OperationUtil.getOperationNamesForPermission(
                PERMISSION_MANAGE, GitSyncServletInterface.class, OPERATION_PLUGIN_PROPERTIES_SET),
            new String[] {TaskConstants.SETTINGS_SAVE});

    return new ExtensionPermission[] {viewPermission, managePermission};
  }

  // -----------------------------------------------------------------------
  // ChannelPlugin
  // -----------------------------------------------------------------------

  @Override
  public void save(Channel channel, ServerEventContext context) {
    if (!enabled || !syncChannels || SyncGuard.isSuppressed() || !isContributor()) {
      return;
    }
    if (isIgnored(channel.getId())) {
      return;
    }

    String username = resolveUsername(context);
    try {
      // Serialise into the user's pending directory (not the main working tree) and record in
      // the user's manifest (also removes from other users' manifests). The file write runs
      // inside the tracker lock so a concurrent reconciliation cannot delete it mid-flight.
      PendingChange change =
          new PendingChange(
              PendingChange.Type.CHANNEL,
              channel.getId(),
              channel.getName(),
              PendingChange.Action.MODIFY,
              channel.getRevision());
      pendingTracker.recordModify(
          username, change, userDir -> new ArtifactSerializer(userDir).serializeChannel(channel));

      logger.info(
          "Pending change recorded: channel '{}' modified by {}", channel.getName(), username);
    } catch (Exception e) {
      logger.error(
          "Failed to track pending change for channel '{}' ({}), but save succeeded in database.",
          channel.getName(),
          channel.getId(),
          e);
      recordFailure(ArtifactType.CHANNEL, Action.SAVE, channel.getId(), channel.getName(), e);
    }
  }

  @Override
  public void remove(Channel channel, ServerEventContext context) {
    if (!enabled || !syncChannels || SyncGuard.isSuppressed() || !isContributor()) {
      return;
    }

    String username = resolveUsername(context);
    try {
      // Always record the DELETE. Checking the base-branch working tree here would miss channels
      // committed to a not-yet-merged feature branch (or merged while the local tree is stale),
      // and Git would resurrect them on the next merge. A DELETE for a never-committed channel is
      // a harmless no-op at commit time.
      pendingTracker.recordDelete(
          username, PendingChange.Type.CHANNEL, channel.getId(), channel.getName(), true);

      logger.info("Pending delete recorded: channel '{}' by {}", channel.getName(), username);
    } catch (Exception e) {
      logger.error(
          "Failed to track pending delete for channel '{}' ({})",
          channel.getName(),
          channel.getId(),
          e);
      recordFailure(ArtifactType.CHANNEL, Action.REMOVE, channel.getId(), channel.getName(), e);
    }
  }

  @Override
  public void deploy(Channel channel, ServerEventContext context) {}

  @Override
  public void deploy(ServerEventContext context) {}

  @Override
  public void undeploy(String channelId, ServerEventContext context) {}

  @Override
  public void undeploy(ServerEventContext context) {}

  // -----------------------------------------------------------------------
  // CodeTemplateServerPlugin
  // -----------------------------------------------------------------------

  @Override
  public void save(CodeTemplateLibrary library, ServerEventContext context) {
    if (!enabled || !syncCodeTemplates || SyncGuard.isSuppressed() || !isContributor()) {
      return;
    }

    String username = resolveUsername(context);
    try {
      PendingChange change =
          new PendingChange(
              PendingChange.Type.CODE_TEMPLATE_LIBRARY,
              library.getId(),
              library.getName(),
              PendingChange.Action.MODIFY,
              library.getRevision());
      pendingTracker.recordModify(
          username,
          change,
          userDir -> new ArtifactSerializer(userDir).serializeCodeTemplateLibrary(library));

      logger.info(
          "Pending change recorded: code template library '{}' modified by {}",
          library.getName(),
          username);
    } catch (Exception e) {
      logger.error(
          "Failed to track pending change for code template library '{}' ({})",
          library.getName(),
          library.getId(),
          e);
      recordFailure(
          ArtifactType.CODE_TEMPLATE_LIBRARY, Action.SAVE, library.getId(), library.getName(), e);
    }
  }

  @Override
  public void remove(CodeTemplateLibrary library, ServerEventContext context) {
    if (!enabled || !syncCodeTemplates || SyncGuard.isSuppressed() || !isContributor()) {
      return;
    }

    String username = resolveUsername(context);
    try {
      // Always record the DELETE — see the channel remove hook for the rationale.
      pendingTracker.recordDelete(
          username,
          PendingChange.Type.CODE_TEMPLATE_LIBRARY,
          library.getId(),
          library.getName(),
          true);

      logger.info(
          "Pending delete recorded: code template library '{}' by {}", library.getName(), username);
    } catch (Exception e) {
      logger.error(
          "Failed to track pending delete for code template library '{}' ({})",
          library.getName(),
          library.getId(),
          e);
      recordFailure(
          ArtifactType.CODE_TEMPLATE_LIBRARY, Action.REMOVE, library.getId(), library.getName(), e);
    }
  }

  @Override
  public void save(CodeTemplate codeTemplate, ServerEventContext context) {
    // Individual code templates are saved as part of their library.
    // The library save hook handles serialisation of all templates.
  }

  @Override
  public void remove(CodeTemplate codeTemplate, ServerEventContext context) {
    // Individual code template removal is handled by the library save hook
    // when the library is re-saved without this template.
  }

  // -----------------------------------------------------------------------
  // REST API support (called from GitSyncServlet)
  // -----------------------------------------------------------------------

  public GitSyncStatus getStatus() {
    status.setEnabled(enabled);
    status.setEnvironmentName(environmentName);
    status.setNodeRole(nodeRole.name());
    if (repoManager != null && repoManager.isInitialised()) {
      status.setRepoInitialised(true);
      try {
        status.setBranch(repoManager.getCurrentBranch());
      } catch (Exception e) {
        logger.debug("Could not get current branch", e);
      }
    }

    // Total pending changes across all users
    if (pendingTracker != null) {
      try {
        int totalPending = 0;
        for (String user : pendingTracker.getUsersWithPending()) {
          totalPending += pendingTracker.getPending(user).size();
        }
        status.setPendingChangeCount(totalPending);
      } catch (Exception e) {
        logger.debug("Failed to count pending changes", e);
      }
    }

    synchronized (syncLog) {
      status.setRecentRecords(new ArrayList<>(syncLog));
    }
    return status;
  }

  public GitSyncStatus triggerFullSync() throws Exception {
    if (!enabled) {
      throw new IllegalStateException("Git Sync is not enabled");
    }
    if (repoManager == null || !repoManager.isInitialised()) {
      throw new IllegalStateException("Git repository is not initialised");
    }

    List<SyncRecord> syncRecords = new ArrayList<>();
    int[] totalCount = {0};
    String fullSyncBranch = "gitsync/fullsync/" + LocalDate.now().toString();

    // The callback runs while the target branch is checked out, just before staging.
    // It writes all artefacts directly to the main working tree on the target branch.
    GitRepoManager.FileOperationCallback fileOps =
        (Path workingTreeRoot) -> {
          ArtifactSerializer wtSerializer = new ArtifactSerializer(workingTreeRoot);

          clearSyncDirectories(workingTreeRoot);

          // Channels
          if (syncChannels) {
            ChannelController channelController =
                ControllerFactory.getFactory().createChannelController();
            List<Channel> channels = channelController.getChannels(null);
            for (Channel channel : channels) {
              if (isIgnored(channel.getId())) continue;
              try {
                wtSerializer.serializeChannel(channel);
                syncRecords.add(
                    SyncRecord.success(
                        ArtifactType.CHANNEL,
                        Action.SYNC,
                        channel.getId(),
                        channel.getName(),
                        null));
                totalCount[0]++;
              } catch (Exception e) {
                logger.error(
                    "Failed to serialise channel '{}' during full sync", channel.getName(), e);
                syncRecords.add(
                    SyncRecord.failure(
                        ArtifactType.CHANNEL,
                        Action.SYNC,
                        channel.getId(),
                        channel.getName(),
                        e.getMessage()));
              }
            }

            if (syncChannelGroups) {
              try {
                List<ChannelGroup> groups = channelController.getChannelGroups(null);
                if (groups != null && !groups.isEmpty()) {
                  wtSerializer.serializeChannelGroups(groups);
                  for (ChannelGroup group : groups) {
                    syncRecords.add(
                        SyncRecord.success(
                            ArtifactType.CHANNEL_GROUP,
                            Action.SYNC,
                            group.getId(),
                            group.getName(),
                            null));
                  }
                  totalCount[0] += groups.size();
                }
              } catch (Exception e) {
                logger.error("Failed to serialise channel groups during full sync", e);
              }
            }
          }

          if (syncCodeTemplates) {
            try {
              CodeTemplateController codeTemplateController =
                  ControllerFactory.getFactory().createCodeTemplateController();
              List<CodeTemplateLibrary> libraries = codeTemplateController.getLibraries(null, true);
              for (CodeTemplateLibrary library : libraries) {
                try {
                  wtSerializer.serializeCodeTemplateLibrary(library);
                  syncRecords.add(
                      SyncRecord.success(
                          ArtifactType.CODE_TEMPLATE_LIBRARY,
                          Action.SYNC,
                          library.getId(),
                          library.getName(),
                          null));
                  totalCount[0]++;
                } catch (Exception e) {
                  logger.error(
                      "Failed to serialise code template library '{}' during full sync",
                      library.getName(),
                      e);
                  syncRecords.add(
                      SyncRecord.failure(
                          ArtifactType.CODE_TEMPLATE_LIBRARY,
                          Action.SYNC,
                          library.getId(),
                          library.getName(),
                          e.getMessage()));
                }
              }
            } catch (Exception e) {
              logger.error("Failed to retrieve code template libraries during full sync", e);
            }
          }

          if (syncGlobalScripts) {
            try {
              ScriptController scriptController =
                  ControllerFactory.getFactory().createScriptController();
              Map<String, String> scripts = scriptController.getGlobalScripts();
              if (scripts != null && !scripts.isEmpty()) {
                wtSerializer.serializeGlobalScripts(scripts);
                syncRecords.add(
                    SyncRecord.success(
                        ArtifactType.GLOBAL_SCRIPT, Action.SYNC, "global", "Global Scripts", null));
                totalCount[0] += scripts.size();
              }
            } catch (Exception e) {
              logger.error("Failed to serialise global scripts during full sync", e);
            }
          }

          try {
            ConfigurationController configController = ConfigurationController.getInstance();
            Map<String, ConfigurationProperty> configProps =
                configController.getConfigurationProperties();
            if (configProps != null && !configProps.isEmpty()) {
              wtSerializer.serializeConfigMapTemplate(configProps);
              syncRecords.add(
                  SyncRecord.success(
                      ArtifactType.CONFIG_MAP,
                      Action.SYNC,
                      "config-map",
                      "Configuration Map Template",
                      null));
              totalCount[0]++;
            }
          } catch (Exception e) {
            logger.error("Failed to serialise config map template during full sync", e);
          }
        };

    // Stage everything under the synced directories
    List<String> pathsToAdd =
        List.of("channels", "code-templates", "global-scripts", "channel-groups", "config-map");

    String message = String.format("[%s] Full sync: %d artefacts", environmentName, totalCount[0]);
    // Note: totalCount is computed inside the callback so the message above is stale (0).
    // Override after the callback runs by using the syncRecords list size.
    // Actually, since the callback hasn't run yet here, we'll let commitToBranch run it
    // and recompute the message after - simpler to use a generic message.
    message = String.format("[%s] Full sync (%s)", environmentName, LocalDate.now());

    String hash = repoManager.commitToBranch(fullSyncBranch, fileOps, pathsToAdd, null, message);

    if (hash != null) {
      // Stamp the commit hash onto all success records and add to log
      for (SyncRecord record : syncRecords) {
        if (record.isSuccess()) {
          record.setCommitHash(hash);
        }
        addLogRecord(record);
      }
      status.recordSuccess(hash);
      eventLogger.logFullSync(totalCount[0], hash);
    }

    return getStatus();
  }

  public String testConnection() throws Exception {
    if (repoManager == null || !repoManager.isInitialised()) {
      throw new IllegalStateException("Git repository is not initialised");
    }
    return repoManager.testConnection();
  }

  /**
   * Nukes the local clone and re-clones from the remote. Also clears all per-user pending change
   * directories.
   */
  public void resetLocalRepo() throws Exception {
    if (!enabled) {
      throw new IllegalStateException("Git Sync is not enabled");
    }
    if (repoManager == null) {
      throw new IllegalStateException("Repository manager not initialised");
    }
    String remoteUrl = currentProperties.getProperty(GitSyncProperties.REMOTE_URL, "");
    repoManager.resetLocalRepo(remoteUrl.isBlank() ? null : remoteUrl);
    // Pending tracker reads from the same repoPath - re-create it
    pendingTracker = new PendingChangeTracker(repoPath());
    status.setRepoInitialised(true);
    logger.info("Local repo reset complete - re-cloned from remote");
  }

  // -----------------------------------------------------------------------
  // Batch commit flow
  // -----------------------------------------------------------------------

  public PendingChangeList getPending(String username) throws IOException {
    if (pendingTracker == null) {
      return new PendingChangeList(username);
    }
    return pendingTracker.getPending(username);
  }

  public List<String> getUsersWithPending() throws IOException {
    if (pendingTracker == null) {
      return Collections.emptyList();
    }
    return pendingTracker.getUsersWithPending();
  }

  public void discardPending(String username) throws IOException {
    if (pendingTracker != null) {
      pendingTracker.clearPending(username);
      logger.info("Discarded pending changes for user {}", LogSanitiser.clean(username));
    }
  }

  /**
   * Commits all pending changes for a user to their feature branch.
   *
   * @param username the user whose pending changes to commit
   * @param customMessage optional commit message (null/empty = auto-generate)
   * @return the commit hash, or null if there was nothing to commit
   */
  public String commitPending(String username, String customMessage) throws Exception {
    if (!enabled) {
      throw new IllegalStateException("Git Sync is not enabled");
    }
    if (repoManager == null || !repoManager.isInitialised()) {
      throw new IllegalStateException("Git repository is not initialised");
    }

    PendingChangeList pending = pendingTracker.getPending(username);
    if (pending.isEmpty()) {
      logger.info("No pending changes for user {}", LogSanitiser.clean(username));
      return null;
    }

    String driftPattern = currentProperties.getProperty(GitSyncProperties.DRIFT_BRANCH_PATTERN, "");
    String targetBranch =
        driftPattern.isBlank()
            ? resolveBranchPattern(username)
            : resolveDriftBranchPattern(username);
    Path userDir = pendingTracker.getUserDir(username);

    // Gather paths to add/remove based on manifest
    List<String> pathsToAdd = new ArrayList<>();
    List<String> pathsToRemove = new ArrayList<>();
    int channelCount = 0;
    int libraryCount = 0;

    for (PendingChange change : pending.getChanges()) {
      // Defence in depth: IDs come from the on-disk manifest; never build repo paths from an ID
      // that could contain path separators or dot segments.
      ArtifactSerializer.requireSafeId(change.getId());
      String path =
          switch (change.getType()) {
            case CHANNEL -> "channels/" + change.getId();
            case CODE_TEMPLATE_LIBRARY -> "code-templates/" + change.getId();
          };
      if (change.getAction() == PendingChange.Action.DELETE) {
        pathsToRemove.add(path);
      } else {
        pathsToAdd.add(path);
      }
      if (change.getType() == PendingChange.Type.CHANNEL) {
        channelCount++;
      } else if (change.getType() == PendingChange.Type.CODE_TEMPLATE_LIBRARY) {
        libraryCount++;
      }
    }

    String message =
        customMessage != null && !customMessage.isBlank()
            ? customMessage
            : buildBatchCommitMessage(username, channelCount, libraryCount);

    // File operations callback: copy from user's pending dir into main working tree
    GitRepoManager.FileOperationCallback fileOps =
        (Path workingTreeRoot) -> {
          for (PendingChange change : pending.getChanges()) {
            if (change.getAction() == PendingChange.Action.DELETE) {
              Path targetPath =
                  switch (change.getType()) {
                    case CHANNEL -> workingTreeRoot.resolve("channels").resolve(change.getId());
                    case CODE_TEMPLATE_LIBRARY ->
                        workingTreeRoot.resolve("code-templates").resolve(change.getId());
                  };
              if (Files.exists(targetPath)) {
                FileUtils.deleteRecursively(targetPath);
              }
            } else {
              Path sourceSubPath =
                  switch (change.getType()) {
                    case CHANNEL -> Paths.get("channels", change.getId());
                    case CODE_TEMPLATE_LIBRARY -> Paths.get("code-templates", change.getId());
                  };
              Path sourceDir = userDir.resolve(sourceSubPath);
              Path targetDir = workingTreeRoot.resolve(sourceSubPath);
              if (Files.exists(sourceDir)) {
                copyRecursively(sourceDir, targetDir);
              }
            }
          }
        };

    // Resolve the OIE user to a Git author identity
    String[] author = resolveAuthorIdentity(username);

    String hash =
        repoManager.commitToBranch(
            targetBranch, fileOps, pathsToAdd, pathsToRemove, message, author[0], author[1]);

    if (hash != null) {
      // Record individual sync records for each change
      for (PendingChange change : pending.getChanges()) {
        ArtifactType artifactType =
            switch (change.getType()) {
              case CHANNEL -> ArtifactType.CHANNEL;
              case CODE_TEMPLATE_LIBRARY -> ArtifactType.CODE_TEMPLATE_LIBRARY;
            };
        Action syncAction =
            change.getAction() == PendingChange.Action.DELETE ? Action.REMOVE : Action.SAVE;
        recordSuccess(artifactType, syncAction, change.getId(), change.getName(), hash);
      }

      logger.info(
          "Committed {} pending changes for user {} to branch {} ({})",
          pending.size(),
          LogSanitiser.clean(username),
          LogSanitiser.clean(targetBranch),
          GitRepoManager.shortHash(hash));
    } else {
      logger.info(
          "Pending changes for user {} produced no diff on branch {} (already up to date)",
          LogSanitiser.clean(username),
          LogSanitiser.clean(targetBranch));
    }

    // Clear exactly the snapshot of changes that was flushed (a null hash means the flush
    // produced no diff, which still consumes the snapshot). Changes recorded by save hooks while
    // the Git operations were in flight are preserved for the next commit rather than wiped.
    pendingTracker.clearPending(username, pending.getChanges());

    return hash;
  }

  private String resolveBranchPattern(String username) {
    String pattern =
        currentProperties.getProperty(
            GitSyncProperties.COMMIT_BRANCH_PATTERN,
            GitSyncProperties.DEFAULT_COMMIT_BRANCH_PATTERN);
    String date = LocalDate.now().toString();
    String sanitisedUsername = username.toLowerCase().replaceAll("[^a-z0-9-]", "-");

    String resolved =
        pattern
            .replace("{username}", sanitisedUsername)
            .replace("{date}", date)
            .replace("{environment}", environmentName)
            .replace(
                "{branch}",
                currentProperties.getProperty(
                    GitSyncProperties.BRANCH, GitSyncProperties.DEFAULT_BRANCH));

    // Sanitise: no leading slash, no double slashes, no trailing slash
    resolved = resolved.replaceAll("/+", "/").replaceAll("^/|/$", "");

    if (resolved.isBlank()) {
      logger.warn("Branch pattern '{}' resolved to empty, using fallback", pattern);
      return "gitsync/" + sanitisedUsername;
    }

    return resolved;
  }

  /**
   * Resolves the drift branch pattern using the same token set as the normal commit branch pattern.
   * Defaults to "prod-drift/{date}" if the stored pattern resolves to empty.
   */
  private String resolveDriftBranchPattern(String username) {
    String pattern = currentProperties.getProperty(GitSyncProperties.DRIFT_BRANCH_PATTERN, "");
    if (pattern.isBlank()) {
      return "prod-drift/" + LocalDate.now();
    }
    String date = LocalDate.now().toString();
    String sanitisedUsername = username.toLowerCase().replaceAll("[^a-z0-9-]", "-");

    String resolved =
        pattern
            .replace("{username}", sanitisedUsername)
            .replace("{date}", date)
            .replace("{environment}", environmentName)
            .replace(
                "{branch}",
                currentProperties.getProperty(
                    GitSyncProperties.BRANCH, GitSyncProperties.DEFAULT_BRANCH));

    resolved = resolved.replaceAll("/+", "/").replaceAll("^/|/$", "");

    if (resolved.isBlank()) {
      return "prod-drift/" + date;
    }
    return resolved;
  }

  private String buildBatchCommitMessage(String username, int channelCount, int libraryCount) {
    StringBuilder sb = new StringBuilder();
    sb.append("[").append(environmentName).append("] ").append(username).append(": ");
    List<String> parts = new ArrayList<>();
    if (channelCount > 0) {
      parts.add(channelCount + " channel" + (channelCount > 1 ? "s" : ""));
    }
    if (libraryCount > 0) {
      parts.add(libraryCount + " code template librar" + (libraryCount > 1 ? "ies" : "y"));
    }
    sb.append(String.join(", ", parts));
    return sb.toString();
  }

  /**
   * Clears the synced artefact directories in the working tree ahead of a full sync, so the
   * snapshot mirrors the current OIE state including artefacts deleted since the last sync —
   * otherwise the snapshot branch resurrects them if merged. commitToBranch stages the resulting
   * deletions. Directories for disabled artefact types are left untouched.
   */
  private void clearSyncDirectories(Path workingTreeRoot) {
    if (syncChannels) {
      clearSyncDirectory(workingTreeRoot, "channels");
    }
    if (syncChannels && syncChannelGroups) {
      clearSyncDirectory(workingTreeRoot, "channel-groups");
    }
    if (syncCodeTemplates) {
      clearSyncDirectory(workingTreeRoot, "code-templates");
    }
    if (syncGlobalScripts) {
      clearSyncDirectory(workingTreeRoot, "global-scripts");
    }
  }

  /**
   * Deletes one synced artefact directory. Failure is logged but not fatal — the snapshot then
   * degrades to the previous additive behaviour.
   */
  private void clearSyncDirectory(Path workingTreeRoot, String dirName) {
    try {
      Path dir = workingTreeRoot.resolve(dirName);
      if (Files.exists(dir)) {
        FileUtils.deleteRecursively(dir);
      }
    } catch (IOException e) {
      logger.warn("Failed to clear {} before full sync; stale artefacts may remain", dirName, e);
    }
  }

  private void copyRecursively(Path source, Path target) throws IOException {
    if (Files.isDirectory(source)) {
      Files.createDirectories(target);
      try (var stream = Files.list(source)) {
        stream.forEach(
            child -> {
              try {
                copyRecursively(child, target.resolve(child.getFileName()));
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
      }
    } else {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Promotes channel configurations from Git to this OIE instance (Direction B). Delegates the
   * heavy lifting to {@link PromotionService}, then handles state persistence and audit logging.
   */
  public PromotionResult promote(PromotionRequest request) throws Exception {
    if (repoManager == null || !repoManager.isInitialised()) {
      throw new IllegalStateException("Git repository is not initialised");
    }

    // Resolve the lastPromotedCommit (fresh mode ignores it)
    String lastPromoted =
        request.isFresh()
            ? ""
            : currentProperties.getProperty(GitSyncProperties.LAST_PROMOTED_COMMIT, "");

    PromotionService svc = new PromotionService(repoManager, serializer);
    PromotionResult result = svc.promote(request, lastPromoted);

    // Persist the promoted commit for the next incremental diff — but only when every artefact
    // imported cleanly. Advancing past a partial failure would drop the failed artefacts out of
    // the next diff and silently never retry them.
    if (!request.isDryRun() && result.isSuccess() && result.getCommitHash() != null) {
      persistLastPromotedCommit(result.getCommitHash());
    } else if (!request.isDryRun() && !result.isSuccess()) {
      logger.warn(
          "Promotion completed with errors - lastPromotedCommit not advanced; "
              + "failed artefacts will be retried on the next promotion");
    }

    // Audit log
    for (SyncRecord record : result.getRecords()) {
      addLogRecord(record);
    }
    if (!request.isDryRun()) {
      eventLogger.logSync(
          "Promotion",
          "PROMOTE (" + result.getChannelsImported() + " channels)",
          result.getCommitHash());
    }

    return result;
  }

  private void persistLastPromotedCommit(String commitHash) {
    try {
      Properties props = new Properties();
      props.putAll(currentProperties);
      props.setProperty(GitSyncProperties.LAST_PROMOTED_COMMIT, commitHash);
      currentProperties = props;
      ControllerFactory.getFactory()
          .createExtensionController()
          .setPluginProperties(PLUGIN_POINT, props);
    } catch (Exception e) {
      logger.warn("Failed to persist lastPromotedCommit", e);
    }
  }

  public List<SyncRecord> getSyncLog(int limit) {
    synchronized (syncLog) {
      int size = Math.min(limit, syncLog.size());
      return new ArrayList<>(syncLog.subList(0, size));
    }
  }

  // -----------------------------------------------------------------------
  // Internal helpers
  // -----------------------------------------------------------------------

  private void applyProperties(Properties properties) {
    enabled = Boolean.parseBoolean(properties.getProperty(GitSyncProperties.ENABLED, "false"));
    environmentName =
        properties.getProperty(
            GitSyncProperties.ENVIRONMENT_NAME, GitSyncProperties.DEFAULT_ENVIRONMENT);
    nodeRole = NodeRole.parse(properties.getProperty(GitSyncProperties.NODE_ROLE));
    syncChannels =
        Boolean.parseBoolean(properties.getProperty(GitSyncProperties.SYNC_CHANNELS, "true"));
    syncCodeTemplates =
        Boolean.parseBoolean(properties.getProperty(GitSyncProperties.SYNC_CODE_TEMPLATES, "true"));
    syncGlobalScripts =
        Boolean.parseBoolean(properties.getProperty(GitSyncProperties.SYNC_GLOBAL_SCRIPTS, "true"));
    syncChannelGroups =
        Boolean.parseBoolean(properties.getProperty(GitSyncProperties.SYNC_CHANNEL_GROUPS, "true"));

    Path repoPath =
        Paths.get(
            properties.getProperty(
                GitSyncProperties.REPO_PATH, GitSyncProperties.DEFAULT_REPO_PATH));
    repoManager = new GitRepoManager(repoPath);
    serializer = new ArtifactSerializer(repoPath);
    pendingTracker = new PendingChangeTracker(repoPath);

    repoManager.setRemoteName(
        properties.getProperty(
            GitSyncProperties.REMOTE_NAME, GitSyncProperties.DEFAULT_REMOTE_NAME));
    repoManager.setBranch(
        properties.getProperty(GitSyncProperties.BRANCH, GitSyncProperties.DEFAULT_BRANCH));
    repoManager.setPushEnabled(
        Boolean.parseBoolean(properties.getProperty(GitSyncProperties.PUSH_ENABLED, "true")));
    repoManager.setPushRetryCount(
        (int)
            Math.min(
                parseLongOrDefault(
                    properties.getProperty(GitSyncProperties.PUSH_RETRY_COUNT),
                    GitSyncProperties.PUSH_RETRY_COUNT,
                    GitSyncProperties.DEFAULT_PUSH_RETRY_COUNT),
                Integer.MAX_VALUE));
    repoManager.setPushRetryDelayMs(
        parseLongOrDefault(
            properties.getProperty(GitSyncProperties.PUSH_RETRY_DELAY_MS),
            GitSyncProperties.PUSH_RETRY_DELAY_MS,
            GitSyncProperties.DEFAULT_PUSH_RETRY_DELAY_MS));
    repoManager.setAuthorName(
        properties.getProperty(
            GitSyncProperties.COMMIT_AUTHOR_NAME, GitSyncProperties.DEFAULT_AUTHOR_NAME));
    repoManager.setAuthorEmail(
        properties.getProperty(
            GitSyncProperties.COMMIT_AUTHOR_EMAIL, GitSyncProperties.DEFAULT_AUTHOR_EMAIL));

    CredentialType credentialType =
        CredentialType.parse(properties.getProperty(GitSyncProperties.CREDENTIAL_TYPE));
    if (credentialType.requiresUsernamePassword()) {
      String username = properties.getProperty(GitSyncProperties.CREDENTIAL_USERNAME, "");
      String storedPassword = properties.getProperty(GitSyncProperties.CREDENTIAL_PASSWORD, "");
      repoManager.setCredentials(username, CredentialStore.decrypt(storedPassword));
    }

    status.setEnabled(enabled);
    status.setEnvironmentName(environmentName);
  }

  /**
   * Resolves an OIE username to a Git author identity ({name, email}). Falls back to the configured
   * commitAuthorName/commitAuthorEmail if the user can't be looked up or has no email set.
   */
  private String[] resolveAuthorIdentity(String username) {
    String fallbackName =
        currentProperties.getProperty(
            GitSyncProperties.COMMIT_AUTHOR_NAME, GitSyncProperties.DEFAULT_AUTHOR_NAME);
    String fallbackEmail =
        currentProperties.getProperty(
            GitSyncProperties.COMMIT_AUTHOR_EMAIL, GitSyncProperties.DEFAULT_AUTHOR_EMAIL);

    try {
      UserController userController = ControllerFactory.getFactory().createUserController();
      User user = userController.getUser(null, username);
      if (user != null) {
        String displayName = buildDisplayName(user, username);
        String email =
            (user.getEmail() != null && !user.getEmail().isBlank())
                ? user.getEmail()
                : username + "@oie.local";
        return new String[] {displayName, email};
      }
    } catch (Exception e) {
      logger.debug(
          "Could not resolve user '{}' for commit author", LogSanitiser.clean(username), e);
    }
    return new String[] {fallbackName, fallbackEmail};
  }

  private String buildDisplayName(User user, String username) {
    StringBuilder sb = new StringBuilder();
    if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
      sb.append(user.getFirstName().trim());
    }
    if (user.getLastName() != null && !user.getLastName().isBlank()) {
      if (sb.length() > 0) sb.append(' ');
      sb.append(user.getLastName().trim());
    }
    if (sb.length() == 0) {
      sb.append(username);
    }
    return sb.toString();
  }

  private String resolveUsername(ServerEventContext context) {
    if (context == null || context.getUserId() == null) {
      return "system";
    }
    Integer userId = context.getUserId();
    if (userId == 0) {
      return "system";
    }
    try {
      UserController userController = ControllerFactory.getFactory().createUserController();
      User user = userController.getUser(userId, null);
      if (user != null && user.getUsername() != null) {
        return user.getUsername();
      }
    } catch (Exception e) {
      logger.debug("Could not resolve username for userId {}", userId, e);
    }
    return "user" + userId;
  }

  private void recordSuccess(
      ArtifactType type, Action action, String id, String name, String hash) {
    SyncRecord record = SyncRecord.success(type, action, id, name, hash);
    addLogRecord(record);
    status.recordSuccess(hash);
    eventLogger.logSync(name, action.name(), hash);
  }

  private void recordFailure(
      ArtifactType type, Action action, String id, String name, Exception e) {
    SyncRecord record = SyncRecord.failure(type, action, id, name, e.getMessage());
    addLogRecord(record);
    status.recordFailure(e.getMessage());
    eventLogger.logSyncFailure(name, action.name().toLowerCase(), e.getMessage());
  }

  private void addLogRecord(SyncRecord record) {
    synchronized (syncLog) {
      syncLog.addFirst(record);
      while (syncLog.size() > MAX_LOG_SIZE) {
        syncLog.removeLast();
      }
    }
  }

  private boolean isIgnored(String channelId) {
    return ignoredChannelIds.contains(channelId);
  }

  /**
   * Parses a numeric property value, falling back when the value is missing or malformed. The
   * warning deliberately logs the property key, not the value — a value mistakenly pasted into the
   * wrong settings field must not end up in the server log.
   */
  private static long parseLongOrDefault(String value, String propertyKey, long fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      logger.warn(
          "Invalid numeric value for property '{}', using default {}", propertyKey, fallback);
      return fallback;
    }
  }

  private Path repoPath() {
    return Paths.get(
        currentProperties.getProperty(
            GitSyncProperties.REPO_PATH, GitSyncProperties.DEFAULT_REPO_PATH));
  }

  private boolean isContributor() {
    return nodeRole.isContributor();
  }

  public NodeRole getNodeRole() {
    return nodeRole;
  }

  private void loadIgnoreFile() {
    Path ignoreFile = repoPath().resolve(".gitsync-ignore");
    if (Files.exists(ignoreFile)) {
      try {
        List<String> lines = Files.readAllLines(ignoreFile, StandardCharsets.UTF_8);
        Set<String> ids = new HashSet<>();
        for (String line : lines) {
          String trimmed = line.trim();
          if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
            ids.add(trimmed);
          }
        }
        ignoredChannelIds = ids;
        logger.info("Loaded {} channel IDs from .gitsync-ignore", ids.size());
      } catch (IOException e) {
        logger.warn("Failed to read .gitsync-ignore", e);
        ignoredChannelIds = Collections.emptySet();
      }
    } else {
      ignoredChannelIds = Collections.emptySet();
    }
  }
}
