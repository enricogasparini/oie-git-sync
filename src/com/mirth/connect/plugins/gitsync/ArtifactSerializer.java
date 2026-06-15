/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.util.ConfigurationProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Serialises OIE artefacts to files in the local Git repository.
 *
 * <p>Layout: channels/{channelId}/channel.xml + channel-metadata.json
 * code-templates/{libraryId}/library.xml + {templateId}.xml global-scripts/{scriptKey}.js
 * channel-groups/{groupId}.xml config-map/config-map-template.json (keys + comments only)
 */
public class ArtifactSerializer {

  private static final Logger logger = LogManager.getLogger(ArtifactSerializer.class);

  /** Shared pretty-printing JSON writer. Jackson's ObjectMapper is thread-safe after config. */
  private static final ObjectWriter JSON_WRITER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writer();

  private final Path repoPath;

  public ArtifactSerializer(Path repoPath) {
    this.repoPath = repoPath;
  }

  // -----------------------------------------------------------------------
  // Channels
  // -----------------------------------------------------------------------

  public String serializeChannel(Channel channel) throws IOException {
    String channelId = requireSafeId(channel.getId());
    Path channelDir = repoPath.resolve("channels").resolve(channelId);
    Files.createDirectories(channelDir);

    Channel clone = channel.clone();
    clone.clearExportData();
    String xml = ObjectXMLSerializer.getInstance().serialize(clone);
    Files.writeString(channelDir.resolve("channel.xml"), xml, StandardCharsets.UTF_8);

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", channelId);
    metadata.put("name", channel.getName());
    metadata.put("revision", channel.getRevision());
    metadata.put("description", channel.getDescription() != null ? channel.getDescription() : "");
    Files.writeString(
        channelDir.resolve("channel-metadata.json"),
        JSON_WRITER.writeValueAsString(metadata) + "\n",
        StandardCharsets.UTF_8);

    logger.debug("Serialised channel '{}' ({})", channel.getName(), channelId);
    return "channels/" + channelId;
  }

  public Channel deserializeChannel(Path channelXmlPath) throws IOException {
    String xml = Files.readString(channelXmlPath, StandardCharsets.UTF_8);
    return ObjectXMLSerializer.getInstance().deserialize(xml, Channel.class);
  }

  // -----------------------------------------------------------------------
  // Code Template Libraries
  // -----------------------------------------------------------------------

  /**
   * Serialises a code template library and its templates. Returns list of relative paths for Git
   * staging.
   */
  public List<String> serializeCodeTemplateLibrary(CodeTemplateLibrary library) throws IOException {
    String libraryId = requireSafeId(library.getId());
    Path libDir = repoPath.resolve("code-templates").resolve(libraryId);
    Files.createDirectories(libDir);

    List<String> paths = new ArrayList<>();
    String basePath = "code-templates/" + libraryId;

    // Serialise library metadata (without full template objects - just IDs)
    CodeTemplateLibrary libClone = library.cloneIfNeeded();
    List<CodeTemplate> templates = libClone.getCodeTemplates();
    libClone.replaceCodeTemplatesWithIds();
    String libXml = ObjectXMLSerializer.getInstance().serialize(libClone);
    Files.writeString(libDir.resolve("library.xml"), libXml, StandardCharsets.UTF_8);
    paths.add(basePath);

    // Serialise each code template as a separate file
    if (templates != null) {
      for (CodeTemplate template : templates) {
        String templateId = requireSafeId(template.getId());
        String templateXml = ObjectXMLSerializer.getInstance().serialize(template);
        Files.writeString(libDir.resolve(templateId + ".xml"), templateXml, StandardCharsets.UTF_8);
        paths.add(basePath + "/" + templateId + ".xml");
      }
    }

    logger.debug(
        "Serialised code template library '{}' ({}) with {} templates",
        library.getName(),
        libraryId,
        templates != null ? templates.size() : 0);
    return paths;
  }

  // -----------------------------------------------------------------------
  // Global Scripts
  // -----------------------------------------------------------------------

  /**
   * Serialises global scripts as individual .js files. Returns list of relative paths for Git
   * staging.
   */
  public List<String> serializeGlobalScripts(Map<String, String> scripts) throws IOException {
    Path scriptsDir = repoPath.resolve("global-scripts");
    Files.createDirectories(scriptsDir);

    List<String> paths = new ArrayList<>();
    for (Map.Entry<String, String> entry : scripts.entrySet()) {
      String key = entry.getKey().toLowerCase();
      String script = entry.getValue();
      if (script != null && !script.isBlank()) {
        String fileName = key + ".js";
        Files.writeString(scriptsDir.resolve(fileName), script, StandardCharsets.UTF_8);
        paths.add("global-scripts/" + fileName);
      }
    }

    logger.debug("Serialised {} global scripts", paths.size());
    return paths;
  }

  // -----------------------------------------------------------------------
  // Channel Groups
  // -----------------------------------------------------------------------

  /**
   * Serialises channel groups as individual XML files (with channel ID refs only). Returns list of
   * relative paths for Git staging.
   */
  public List<String> serializeChannelGroups(List<ChannelGroup> groups) throws IOException {
    Path groupsDir = repoPath.resolve("channel-groups");
    Files.createDirectories(groupsDir);

    List<String> paths = new ArrayList<>();
    for (ChannelGroup group : groups) {
      ChannelGroup clone = group.cloneIfNeeded();
      clone.replaceChannelsWithIds();
      String xml = ObjectXMLSerializer.getInstance().serialize(clone);
      String fileName = requireSafeId(group.getId()) + ".xml";
      Files.writeString(groupsDir.resolve(fileName), xml, StandardCharsets.UTF_8);
      paths.add("channel-groups/" + fileName);
    }

    logger.debug("Serialised {} channel groups", paths.size());
    return paths;
  }

  // -----------------------------------------------------------------------
  // Configuration Map Template
  // -----------------------------------------------------------------------

  /** Serialises the config map as a template (keys + comments only, NO values). */
  public String serializeConfigMapTemplate(Map<String, ConfigurationProperty> properties)
      throws IOException {
    Path configDir = repoPath.resolve("config-map");
    Files.createDirectories(configDir);

    // Stable ordering (LinkedHashMap) so diffs are meaningful across syncs.
    Map<String, Map<String, String>> template = new LinkedHashMap<>();
    for (Map.Entry<String, ConfigurationProperty> entry : properties.entrySet()) {
      Map<String, String> keyBlock = new LinkedHashMap<>();
      String comment = entry.getValue().getComment();
      keyBlock.put("comment", comment != null ? comment : "");
      template.put(entry.getKey(), keyBlock);
    }
    Files.writeString(
        configDir.resolve("config-map-template.json"),
        JSON_WRITER.writeValueAsString(template) + "\n",
        StandardCharsets.UTF_8);

    logger.debug("Serialised config map template with {} keys", properties.size());
    return "config-map";
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /**
   * Validates that an artefact ID is safe to embed in a filesystem path. OIE IDs are normally
   * UUIDs, but the REST API accepts arbitrary ID strings on import, and these IDs are used as
   * directory names that are later written to and recursively deleted. Rejects anything containing
   * a path separator and anything that is only dots (".", ".."), which would otherwise resolve
   * outside the intended directory.
   *
   * @return the ID unchanged, for inline use
   * @throws IOException if the ID is null, blank, or unsafe
   */
  static String requireSafeId(String id) throws IOException {
    if (id == null || id.isBlank()) {
      throw new IOException("Artefact ID is null or blank");
    }
    if (id.contains("/") || id.contains("\\") || id.replace(".", "").isBlank()) {
      throw new IOException("Artefact ID contains unsafe path characters: " + id);
    }
    return id;
  }
}
