/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.ChannelMetadata;
import com.mirth.connect.model.ChannelTag;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.gitsync.model.PromotionRequest;
import com.mirth.connect.plugins.gitsync.model.PromotionResult;
import com.mirth.connect.plugins.gitsync.model.SyncRecord;
import com.mirth.connect.plugins.gitsync.model.SyncRecord.Action;
import com.mirth.connect.plugins.gitsync.model.SyncRecord.ArtifactType;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.CodeTemplateController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import com.mirth.connect.server.controllers.ScriptController;
import com.mirth.connect.util.ConfigurationProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Encapsulates the Direction B (Git to OIE) promotion flow, extracted from GitSyncPlugin to keep
 * the facade class lean and each per-artefact-type handler independently testable.
 */
class PromotionService {

  private static final Logger logger = LogManager.getLogger(PromotionService.class);

  private final GitRepoManager repoManager;
  private final ArtifactSerializer serializer;

  PromotionService(GitRepoManager repoManager, ArtifactSerializer serializer) {
    this.repoManager = repoManager;
    this.serializer = serializer;
  }

  /**
   * Runs the full promotion flow: fetch, detect changes, preview or apply per-artefact-type, and
   * optionally deploy.
   *
   * <p>The fetch, change detection, and apply phases run under the repository lock so a concurrent
   * "Commit to Git" cannot switch the working tree to a feature branch while promotion is reading
   * files from it. Deployment happens after the lock is released — it operates on the database, not
   * the working tree.
   *
   * @param request the promotion parameters
   * @param lastPromotedCommit the commit hash from the previous promotion (empty or null for first
   *     run)
   * @return the result including per-artefact records, warnings, and errors
   */
  PromotionResult promote(PromotionRequest request, String lastPromotedCommit) throws Exception {
    PromotionResult result = new PromotionResult();
    result.setDryRun(request.isDryRun());

    Set<String> deployChannelIds =
        repoManager.withRepoLock(() -> promoteUnderLock(request, lastPromotedCommit, result));

    if (!request.isDryRun() && request.isDeploy() && !deployChannelIds.isEmpty()) {
      deployChannels(deployChannelIds, result);
    }

    result.setSuccess(result.getErrors().isEmpty());
    return result;
  }

  /**
   * The lock-held portion of the promotion flow. Resets the working tree to the requested commit
   * (or the remote tip of the base branch), detects changes, and previews or applies them. Returns
   * the IDs of the channels that were actually imported and are eligible for deployment.
   */
  private Set<String> promoteUnderLock(
      PromotionRequest request, String lastPromotedCommit, PromotionResult result)
      throws Exception {
    // Fetch and reset the working tree to the commit being promoted, so every file read below
    // belongs to that commit — not to whatever HEAD has since moved to.
    String targetCommit = repoManager.fetchAndReset(request.getCommitHash());
    result.setCommitHash(targetCommit);

    // Detect what changed
    String fromCommit =
        (lastPromotedCommit == null || lastPromotedCommit.isBlank()) ? null : lastPromotedCommit;
    ChangedArtifacts changed = detectChanges(fromCommit, targetCommit);

    // Filter to requested channel IDs if specified. The filter applies to channels only; changed
    // libraries, groups, global scripts, and the config map are still imported in full.
    if (request.getChannelIds() != null && !request.getChannelIds().isEmpty()) {
      changed.channelIds.retainAll(request.getChannelIds());
    }

    if (changed.isEmpty()) {
      result.setSuccess(true);
      result.addWarning("No changes detected since last promotion.");
      return Collections.emptySet();
    }

    logger.info(
        "Promotion: {} channels, {} libraries, {} groups, globalScripts={} (dryRun={})",
        changed.channelIds.size(),
        changed.libraryIds.size(),
        changed.channelGroupIds.size(),
        changed.globalScriptsChanged,
        request.isDryRun());

    if (request.isDryRun()) {
      buildPreview(changed, targetCommit, result);
      return Collections.emptySet();
    }
    return applyChanges(changed, request, targetCommit, result);
  }

  // ---------------------------------------------------------------------------
  // Change detection
  // ---------------------------------------------------------------------------

  ChangedArtifacts detectChanges(String fromCommit, String targetCommit) throws Exception {
    List<String> channelPaths = repoManager.getChangedPaths(fromCommit, targetCommit, "channels/");
    List<String> codeTmplPaths =
        repoManager.getChangedPaths(fromCommit, targetCommit, "code-templates/");
    List<String> globalScriptPaths =
        repoManager.getChangedPaths(fromCommit, targetCommit, "global-scripts/");
    List<String> channelGroupPaths =
        repoManager.getChangedPaths(fromCommit, targetCommit, "channel-groups/");

    Set<String> channelIds = extractIds(channelPaths, "channels", false);
    Set<String> libraryIds = extractIds(codeTmplPaths, "code-templates", false);
    Set<String> groupIds = extractIds(channelGroupPaths, "channel-groups", true);

    return new ChangedArtifacts(channelIds, libraryIds, groupIds, !globalScriptPaths.isEmpty());
  }

  private Set<String> extractIds(List<String> paths, String prefix, boolean stripXmlSuffix) {
    Set<String> ids = new HashSet<>();
    for (String path : paths) {
      String[] parts = path.split("/");
      if (parts.length >= 2 && prefix.equals(parts[0])) {
        String id = parts[1];
        if (stripXmlSuffix && id.endsWith(".xml")) {
          id = id.replace(".xml", "");
        }
        try {
          ids.add(ArtifactSerializer.requireSafeId(id));
        } catch (IOException e) {
          // A hostile or corrupt repository could carry IDs like ".." that would resolve outside
          // the artefact directories when used as path segments below.
          logger.warn("Skipping artefact with unsafe ID in Git path '{}'", path);
        }
      }
    }
    return ids;
  }

  // ---------------------------------------------------------------------------
  // Dry-run preview
  // ---------------------------------------------------------------------------

  private void buildPreview(ChangedArtifacts changed, String targetCommit, PromotionResult result) {
    previewChannels(changed.channelIds, targetCommit, result);
    previewCodeTemplateLibraries(changed.libraryIds, targetCommit, result);
    previewChannelGroups(changed.channelGroupIds, targetCommit, result);
    previewGlobalScripts(changed.globalScriptsChanged, targetCommit, result);
    result.setChannelsImported(changed.channelIds.size());
  }

  private void previewChannels(Set<String> channelIds, String commit, PromotionResult result) {
    Path channelsDir = repoManager.getRepoPath().resolve("channels");
    for (String channelId : channelIds) {
      Path channelXml = channelsDir.resolve(channelId).resolve("channel.xml");
      if (Files.exists(channelXml)) {
        try {
          Channel channel = serializer.deserializeChannel(channelXml);
          result.addRecord(
              SyncRecord.success(
                  ArtifactType.CHANNEL, Action.PROMOTE, channelId, channel.getName(), commit));
        } catch (Exception e) {
          result.addRecord(
              SyncRecord.failure(
                  ArtifactType.CHANNEL,
                  Action.PROMOTE,
                  channelId,
                  channelId,
                  "Failed to deserialise: " + e.getMessage()));
        }
      } else {
        result.addRecord(
            SyncRecord.success(ArtifactType.CHANNEL, Action.REMOVE, channelId, channelId, commit));
      }
    }
  }

  private void previewCodeTemplateLibraries(
      Set<String> libraryIds, String commit, PromotionResult result) {
    Path codeTmplDir = repoManager.getRepoPath().resolve("code-templates");
    for (String libraryId : libraryIds) {
      Path libXml = codeTmplDir.resolve(libraryId).resolve("library.xml");
      Action action = Files.exists(libXml) ? Action.PROMOTE : Action.REMOVE;
      result.addRecord(
          SyncRecord.success(
              ArtifactType.CODE_TEMPLATE_LIBRARY, action, libraryId, libraryId, commit));
    }
  }

  private void previewChannelGroups(Set<String> groupIds, String commit, PromotionResult result) {
    Path groupsDir = repoManager.getRepoPath().resolve("channel-groups");
    for (String groupId : groupIds) {
      Path groupXml = groupsDir.resolve(groupId + ".xml");
      Action action = Files.exists(groupXml) ? Action.PROMOTE : Action.REMOVE;
      result.addRecord(
          SyncRecord.success(ArtifactType.CHANNEL_GROUP, action, groupId, groupId, commit));
    }
  }

  private void previewGlobalScripts(
      boolean globalScriptsChanged, String commit, PromotionResult result) {
    if (globalScriptsChanged) {
      result.addRecord(
          SyncRecord.success(
              ArtifactType.GLOBAL_SCRIPT, Action.PROMOTE, "global", "Global Scripts", commit));
    }
  }

  // ---------------------------------------------------------------------------
  // Apply changes
  // ---------------------------------------------------------------------------

  private Set<String> applyChanges(
      ChangedArtifacts changed,
      PromotionRequest request,
      String targetCommit,
      PromotionResult result)
      throws Exception {

    Set<String> deployChannelIds = new HashSet<>();

    SyncGuard.runSuppressed(
        () -> {
          importChannels(changed.channelIds, request, targetCommit, result, deployChannelIds);
          importCodeTemplateLibraries(changed.libraryIds, request, targetCommit, result);
          importChannelGroups(changed.channelGroupIds, request, targetCommit, result);
          importGlobalScripts(changed.globalScriptsChanged, targetCommit, result);
          mergeConfigMap(targetCommit, result);
          return null;
        });

    result.setChannelsImported(deployChannelIds.size());
    return deployChannelIds;
  }

  private void importChannels(
      Set<String> channelIds,
      PromotionRequest request,
      String commit,
      PromotionResult result,
      Set<String> deployChannelIds) {

    ChannelController channelController = ControllerFactory.getFactory().createChannelController();
    ConfigurationController configController = ConfigurationController.getInstance();
    Path channelsDir = repoManager.getRepoPath().resolve("channels");

    for (String channelId : channelIds) {
      Path channelXml = channelsDir.resolve(channelId).resolve("channel.xml");

      if (Files.exists(channelXml)) {
        try {
          Channel channel = serializer.deserializeChannel(channelXml);
          preserveTargetMetadata(channel, configController);
          boolean updated =
              channelController.updateChannel(
                  channel,
                  ServerEventContext.SYSTEM_USER_EVENT_CONTEXT,
                  request.isOverwrite(),
                  null);
          if (updated) {
            result.addRecord(
                SyncRecord.success(
                    ArtifactType.CHANNEL, Action.PROMOTE, channelId, channel.getName(), commit));
            deployChannelIds.add(channelId);
          } else {
            String reason =
                "Skipped: channel is locally modified on the target and overwrite=false";
            result.addRecord(
                SyncRecord.failure(
                    ArtifactType.CHANNEL, Action.PROMOTE, channelId, channel.getName(), reason));
            result.addError("Channel " + channelId + ": " + reason);
          }
        } catch (Exception e) {
          logger.error("Failed to import channel {} during promotion", channelId, e);
          result.addRecord(
              SyncRecord.failure(
                  ArtifactType.CHANNEL, Action.PROMOTE, channelId, channelId, e.getMessage()));
          result.addError("Channel " + channelId + ": " + e.getMessage());
        }
      } else {
        removeChannel(channelController, channelId, commit, result);
      }
    }
  }

  /**
   * Carries the target's existing channel metadata and tags onto the imported channel. The channel
   * XML in Git deliberately excludes exportData, so without this {@code updateChannel} would
   * replace the target's metadata with defaults — re-enabling deliberately disabled channels,
   * resetting pruning settings, and stripping the channel from every tag.
   */
  private void preserveTargetMetadata(Channel channel, ConfigurationController configController) {
    ChannelMetadata existing = configController.getChannelMetadata().get(channel.getId());
    if (existing != null) {
      channel.getExportData().setMetadata(existing);
    }
    List<ChannelTag> existingTags = new ArrayList<>();
    for (ChannelTag tag : configController.getChannelTags()) {
      if (tag.getChannelIds().contains(channel.getId())) {
        existingTags.add(tag);
      }
    }
    channel.getExportData().setChannelTags(existingTags);
  }

  private void removeChannel(
      ChannelController channelController,
      String channelId,
      String commit,
      PromotionResult result) {
    try {
      Channel existing = channelController.getChannelById(channelId);
      if (existing != null) {
        channelController.removeChannel(existing, ServerEventContext.SYSTEM_USER_EVENT_CONTEXT);
        result.addRecord(
            SyncRecord.success(
                ArtifactType.CHANNEL, Action.REMOVE, channelId, existing.getName(), commit));
      }
    } catch (Exception e) {
      logger.error("Failed to remove channel {} during promotion", channelId, e);
      result.addRecord(
          SyncRecord.failure(
              ArtifactType.CHANNEL, Action.REMOVE, channelId, channelId, e.getMessage()));
      result.addError("Remove channel " + channelId + ": " + e.getMessage());
    }
  }

  private void importCodeTemplateLibraries(
      Set<String> libraryIds, PromotionRequest request, String commit, PromotionResult result) {
    if (libraryIds.isEmpty()) {
      return;
    }

    try {
      CodeTemplateController ctController =
          ControllerFactory.getFactory().createCodeTemplateController();
      List<CodeTemplateLibrary> updatedLibraries = new ArrayList<>();
      List<CodeTemplate> updatedTemplates = new ArrayList<>();
      Map<String, Set<String>> gitTemplateIdsByLibrary = new LinkedHashMap<>();
      Set<String> removedLibraryIds = new HashSet<>();

      for (String libraryId : libraryIds) {
        Path libDir = repoManager.getRepoPath().resolve("code-templates").resolve(libraryId);
        Path libXml = libDir.resolve("library.xml");
        if (Files.exists(libXml)) {
          deserialiseAndCollectLibrary(
              libDir,
              libXml,
              libraryId,
              commit,
              result,
              updatedLibraries,
              updatedTemplates,
              gitTemplateIdsByLibrary);
        } else {
          removedLibraryIds.add(libraryId);
          result.addRecord(
              SyncRecord.success(
                  ArtifactType.CODE_TEMPLATE_LIBRARY, Action.REMOVE, libraryId, libraryId, commit));
        }
      }

      if (!updatedLibraries.isEmpty() || !removedLibraryIds.isEmpty()) {
        // The engine treats the library list as the COMPLETE set and deletes any library not in
        // it, so the changed libraries must be merged with the unchanged existing ones. Template
        // content is only persisted from the updatedCodeTemplates parameter; the bodies set on
        // the library objects are replaced with ID stubs by the engine.
        List<CodeTemplateLibrary> existing = ctController.getLibraries(null, false);
        List<CodeTemplateLibrary> completeLibraries =
            mergeLibraries(existing, updatedLibraries, removedLibraryIds);
        Set<String> removedTemplateIds =
            computeRemovedTemplateIds(existing, removedLibraryIds, gitTemplateIdsByLibrary);

        ctController.updateLibrariesAndTemplates(
            completeLibraries,
            removedLibraryIds,
            updatedTemplates,
            removedTemplateIds,
            ServerEventContext.SYSTEM_USER_EVENT_CONTEXT,
            request.isOverwrite());
      }
    } catch (Exception e) {
      logger.error("Failed to import code template libraries during promotion", e);
      result.addError("Code templates: " + e.getMessage());
    }
  }

  /**
   * Merges the libraries changed in Git with the unchanged existing libraries into the complete set
   * the engine expects. Removed libraries are excluded; updated ones replace their existing
   * counterparts. Package-private for testability.
   */
  static List<CodeTemplateLibrary> mergeLibraries(
      List<CodeTemplateLibrary> existing,
      List<CodeTemplateLibrary> updated,
      Set<String> removedIds) {
    Map<String, CodeTemplateLibrary> byId = new LinkedHashMap<>();
    for (CodeTemplateLibrary library : existing) {
      if (!removedIds.contains(library.getId())) {
        byId.put(library.getId(), library);
      }
    }
    for (CodeTemplateLibrary library : updated) {
      byId.put(library.getId(), library);
    }
    return new ArrayList<>(byId.values());
  }

  /**
   * Computes the IDs of code templates that must be removed on the target: every template of a
   * removed library, plus templates of an updated library that no longer have a file in Git.
   * Package-private for testability.
   *
   * @param existing the target's current libraries with template ID stubs
   * @param removedLibraryIds libraries whose directory is gone from Git
   * @param gitTemplateIdsByLibrary per updated library, the template IDs present in Git
   */
  static Set<String> computeRemovedTemplateIds(
      List<CodeTemplateLibrary> existing,
      Set<String> removedLibraryIds,
      Map<String, Set<String>> gitTemplateIdsByLibrary) {
    Set<String> removed = new HashSet<>();
    for (CodeTemplateLibrary library : existing) {
      List<CodeTemplate> templates = library.getCodeTemplates();
      if (templates == null) {
        continue;
      }
      if (removedLibraryIds.contains(library.getId())) {
        for (CodeTemplate template : templates) {
          removed.add(template.getId());
        }
      } else {
        Set<String> gitIds = gitTemplateIdsByLibrary.get(library.getId());
        if (gitIds != null) {
          for (CodeTemplate template : templates) {
            if (!gitIds.contains(template.getId())) {
              removed.add(template.getId());
            }
          }
        }
      }
    }
    return removed;
  }

  private void deserialiseAndCollectLibrary(
      Path libDir,
      Path libXml,
      String libraryId,
      String commit,
      PromotionResult result,
      List<CodeTemplateLibrary> librariesToUpdate,
      List<CodeTemplate> templatesToUpdate,
      Map<String, Set<String>> gitTemplateIdsByLibrary) {
    try {
      String xml = Files.readString(libXml, StandardCharsets.UTF_8);
      CodeTemplateLibrary library =
          (CodeTemplateLibrary)
              ObjectXMLSerializer.getInstance().deserialize(xml, CodeTemplateLibrary.class);

      List<CodeTemplate> templates = new ArrayList<>();
      try (var s = Files.list(libDir)) {
        for (Path tmplFile : s.toList()) {
          Path fileNamePath = tmplFile.getFileName();
          if (fileNamePath == null) {
            continue;
          }
          String fileName = fileNamePath.toString();
          if (!fileName.endsWith(".xml") || "library.xml".equals(fileName)) {
            continue;
          }
          String tmplXml = Files.readString(tmplFile, StandardCharsets.UTF_8);
          CodeTemplate tmpl =
              (CodeTemplate)
                  ObjectXMLSerializer.getInstance().deserialize(tmplXml, CodeTemplate.class);
          templates.add(tmpl);
        }
      }
      library.setCodeTemplates(templates);
      librariesToUpdate.add(library);
      templatesToUpdate.addAll(templates);
      Set<String> templateIds = new HashSet<>();
      for (CodeTemplate template : templates) {
        templateIds.add(template.getId());
      }
      gitTemplateIdsByLibrary.put(libraryId, templateIds);
      result.addRecord(
          SyncRecord.success(
              ArtifactType.CODE_TEMPLATE_LIBRARY,
              Action.PROMOTE,
              libraryId,
              library.getName(),
              commit));
    } catch (Exception e) {
      logger.error("Failed to deserialise code template library {}", libraryId, e);
      result.addError("Code template library " + libraryId + ": " + e.getMessage());
    }
  }

  private void importChannelGroups(
      Set<String> groupIds, PromotionRequest request, String commit, PromotionResult result) {
    if (groupIds.isEmpty()) {
      return;
    }

    try {
      Set<ChannelGroup> updatedGroups = new HashSet<>();
      Set<String> removedGroupIds = new HashSet<>();

      for (String groupId : groupIds) {
        Path groupXml =
            repoManager.getRepoPath().resolve("channel-groups").resolve(groupId + ".xml");
        if (Files.exists(groupXml)) {
          try {
            String xml = Files.readString(groupXml, StandardCharsets.UTF_8);
            ChannelGroup group =
                (ChannelGroup)
                    ObjectXMLSerializer.getInstance().deserialize(xml, ChannelGroup.class);
            updatedGroups.add(group);
            result.addRecord(
                SyncRecord.success(
                    ArtifactType.CHANNEL_GROUP, Action.PROMOTE, groupId, group.getName(), commit));
          } catch (Exception e) {
            logger.error("Failed to deserialise channel group {}", groupId, e);
            result.addError("Channel group " + groupId + ": " + e.getMessage());
          }
        } else {
          removedGroupIds.add(groupId);
          result.addRecord(
              SyncRecord.success(
                  ArtifactType.CHANNEL_GROUP, Action.REMOVE, groupId, groupId, commit));
        }
      }

      if (!updatedGroups.isEmpty() || !removedGroupIds.isEmpty()) {
        // The engine treats the passed set as the COMPLETE set of groups and deletes any group
        // not in it, so the changed groups must be merged with the unchanged existing ones.
        ChannelController channelController =
            ControllerFactory.getFactory().createChannelController();
        Set<ChannelGroup> completeGroups =
            mergeGroups(channelController.getChannelGroups(null), updatedGroups, removedGroupIds);
        channelController.updateChannelGroups(
            completeGroups, removedGroupIds, request.isOverwrite());
      }
    } catch (Exception e) {
      logger.error("Failed to import channel groups during promotion", e);
      result.addError("Channel groups: " + e.getMessage());
    }
  }

  /**
   * Merges the groups changed in Git with the unchanged existing groups into the complete set the
   * engine expects. Removed groups are excluded; updated ones replace their existing counterparts.
   * Package-private for testability.
   */
  static Set<ChannelGroup> mergeGroups(
      List<ChannelGroup> existing, Set<ChannelGroup> updated, Set<String> removedIds) {
    Map<String, ChannelGroup> byId = new LinkedHashMap<>();
    for (ChannelGroup group : existing) {
      if (!removedIds.contains(group.getId())) {
        byId.put(group.getId(), group);
      }
    }
    for (ChannelGroup group : updated) {
      byId.put(group.getId(), group);
    }
    return new LinkedHashSet<>(byId.values());
  }

  private void importGlobalScripts(
      boolean globalScriptsChanged, String commit, PromotionResult result) {
    if (!globalScriptsChanged) {
      return;
    }

    try {
      Path scriptsDir = repoManager.getRepoPath().resolve("global-scripts");
      Map<String, String> newScripts = new LinkedHashMap<>();
      String[] keys = {"Deploy", "Undeploy", "Preprocessor", "Postprocessor"};
      for (String key : keys) {
        Path scriptFile = scriptsDir.resolve(key.toLowerCase() + ".js");
        // A script file deleted in Git blanks the corresponding script on the target — the
        // engine's setGlobalScripts puts per key, so omitting the key would silently keep the
        // old content. This mirrors the export side, which only writes non-blank scripts.
        newScripts.put(
            key,
            Files.exists(scriptFile) ? Files.readString(scriptFile, StandardCharsets.UTF_8) : "");
      }
      ScriptController scriptController = ControllerFactory.getFactory().createScriptController();
      scriptController.setGlobalScripts(newScripts);
      result.addRecord(
          SyncRecord.success(
              ArtifactType.GLOBAL_SCRIPT, Action.PROMOTE, "global", "Global Scripts", commit));
    } catch (Exception e) {
      logger.error("Failed to import global scripts during promotion", e);
      result.addError("Global scripts: " + e.getMessage());
    }
  }

  private void mergeConfigMap(String commit, PromotionResult result) {
    try {
      Path configMapTemplate =
          repoManager.getRepoPath().resolve("config-map").resolve("config-map-template.json");
      if (!Files.exists(configMapTemplate)) {
        return;
      }
      Map<String, String> templateEntries = parseConfigMapTemplate(configMapTemplate);
      if (templateEntries.isEmpty()) {
        return;
      }

      ConfigurationController configController = ConfigurationController.getInstance();
      Map<String, ConfigurationProperty> existing = configController.getConfigurationProperties();
      Map<String, ConfigurationProperty> merged = new LinkedHashMap<>(existing);
      int added = 0;
      for (Map.Entry<String, String> entry : templateEntries.entrySet()) {
        if (!merged.containsKey(entry.getKey())) {
          merged.put(entry.getKey(), new ConfigurationProperty("", entry.getValue()));
          added++;
        }
      }
      if (added > 0) {
        configController.setConfigurationProperties(merged, true);
        result.addRecord(
            SyncRecord.success(
                ArtifactType.CONFIG_MAP,
                Action.PROMOTE,
                "config-map",
                "Configuration Map (+" + added + " keys)",
                commit));
        result.addWarning(
            added
                + " new configuration map keys added with empty values"
                + " - set them in Settings > Configuration Map.");
      }
    } catch (Exception e) {
      logger.error("Failed to merge config map template during promotion", e);
      result.addWarning("Config map merge skipped: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Deploy
  // ---------------------------------------------------------------------------

  private void deployChannels(Set<String> deployChannelIds, PromotionResult result) {
    try {
      EngineController engineController = ControllerFactory.getFactory().createEngineController();
      engineController.deployChannels(
          deployChannelIds, ServerEventContext.SYSTEM_USER_EVENT_CONTEXT, null, null);
      result.setChannelsDeployed(deployChannelIds.size());
      logger.info("Deployed {} channels after promotion", deployChannelIds.size());
    } catch (Exception e) {
      logger.error("Failed to deploy channels after promotion", e);
      result.addError("Deploy failed: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Parses the config-map template into an ordered map of key to comment. The template carries keys
   * and comments only — values are deliberately never committed to Git.
   */
  Map<String, String> parseConfigMapTemplate(Path templatePath) throws IOException {
    String content = Files.readString(templatePath, StandardCharsets.UTF_8);
    JsonNode root = new ObjectMapper().readTree(content);
    if (root == null || !root.isObject()) {
      return Collections.emptyMap();
    }
    Map<String, String> entries = new LinkedHashMap<>();
    root.fields()
        .forEachRemaining(
            field -> {
              JsonNode comment =
                  field.getValue().isObject() ? field.getValue().get("comment") : null;
              entries.put(
                  field.getKey(), comment != null && comment.isTextual() ? comment.asText() : "");
            });
    return entries;
  }

  // ---------------------------------------------------------------------------
  // Inner record for change detection results
  // ---------------------------------------------------------------------------

  static class ChangedArtifacts {
    final Set<String> channelIds;
    final Set<String> libraryIds;
    final Set<String> channelGroupIds;
    final boolean globalScriptsChanged;

    ChangedArtifacts(
        Set<String> channelIds,
        Set<String> libraryIds,
        Set<String> channelGroupIds,
        boolean globalScriptsChanged) {
      this.channelIds = channelIds;
      this.libraryIds = libraryIds;
      this.channelGroupIds = channelGroupIds;
      this.globalScriptsChanged = globalScriptsChanged;
    }

    boolean isEmpty() {
      return channelIds.isEmpty()
          && libraryIds.isEmpty()
          && channelGroupIds.isEmpty()
          && !globalScriptsChanged;
    }
  }
}
