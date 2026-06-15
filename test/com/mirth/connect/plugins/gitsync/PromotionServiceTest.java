/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the pure/testable helpers in {@link PromotionService}: change detection,
 * ID extraction, config-map template parsing, and the ChangedArtifacts record. The
 * import methods that call OIE controllers are not testable without the full lifecycle.
 */
class PromotionServiceTest {

  // -----------------------------------------------------------------------
  // parseConfigMapTemplate
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("parseConfigMapTemplate")
  class ConfigMapTemplateTests {

    @Test
    @DisplayName("Extracts top-level keys with their comments from valid JSON object")
    void extractsKeysAndComments(@TempDir Path tmp) throws IOException {
      Path template = tmp.resolve("config-map-template.json");
      Files.writeString(template, """
          {
            "db.host": {"comment": "Database hostname"},
            "db.port": {"comment": "Database port"},
            "api.key": {}
          }
          """, StandardCharsets.UTF_8);

      PromotionService svc = new PromotionService(null, null);
      Map<String, String> entries = svc.parseConfigMapTemplate(template);

      assertEquals(3, entries.size());
      assertEquals("Database hostname", entries.get("db.host"));
      assertEquals("Database port", entries.get("db.port"));
      assertEquals("", entries.get("api.key"), "Missing comment defaults to empty string");
    }

    @Test
    @DisplayName("Returns empty map for empty JSON object")
    void emptyObject(@TempDir Path tmp) throws IOException {
      Path template = tmp.resolve("config-map-template.json");
      Files.writeString(template, "{}", StandardCharsets.UTF_8);

      PromotionService svc = new PromotionService(null, null);
      Map<String, String> entries = svc.parseConfigMapTemplate(template);

      assertTrue(entries.isEmpty());
    }

    @Test
    @DisplayName("Returns empty map for JSON array (non-object)")
    void jsonArray(@TempDir Path tmp) throws IOException {
      Path template = tmp.resolve("config-map-template.json");
      Files.writeString(template, "[\"a\", \"b\"]", StandardCharsets.UTF_8);

      PromotionService svc = new PromotionService(null, null);
      Map<String, String> entries = svc.parseConfigMapTemplate(template);

      assertTrue(entries.isEmpty());
    }

    @Test
    @DisplayName("Returns empty map for null JSON root")
    void nullJson(@TempDir Path tmp) throws IOException {
      Path template = tmp.resolve("config-map-template.json");
      Files.writeString(template, "null", StandardCharsets.UTF_8);

      PromotionService svc = new PromotionService(null, null);
      Map<String, String> entries = svc.parseConfigMapTemplate(template);

      assertTrue(entries.isEmpty());
    }
  }

  // -----------------------------------------------------------------------
  // Complete-set merge helpers. The engine's updateChannelGroups and
  // updateLibrariesAndTemplates treat the passed collection as the COMPLETE
  // set and delete anything not in it — these helpers protect unchanged
  // artefacts on the target from being deleted by a partial promotion.
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("mergeGroups")
  class MergeGroupsTests {

    private ChannelGroup group(String id, String name) {
      ChannelGroup g = new ChannelGroup(name, "");
      g.setId(id);
      return g;
    }

    @Test
    @DisplayName("Unchanged existing groups are preserved alongside updated ones")
    void preservesUnchangedGroups() {
      List<ChannelGroup> existing = List.of(group("g1", "One"), group("g2", "Two"));
      ChannelGroup updatedG2 = group("g2", "Two (renamed)");
      Set<ChannelGroup> merged =
          PromotionService.mergeGroups(existing, Set.of(updatedG2), Set.of());

      assertEquals(2, merged.size(), "g1 must survive a promotion that only touches g2");
      assertTrue(merged.stream().anyMatch(g -> "g1".equals(g.getId())));
      assertTrue(merged.stream().anyMatch(
          g -> "g2".equals(g.getId()) && "Two (renamed)".equals(g.getName())));
    }

    @Test
    @DisplayName("Removed groups are excluded from the complete set")
    void excludesRemovedGroups() {
      List<ChannelGroup> existing = List.of(group("g1", "One"), group("g2", "Two"));
      Set<ChannelGroup> merged =
          PromotionService.mergeGroups(existing, Set.of(), Set.of("g1"));

      assertEquals(1, merged.size());
      assertTrue(merged.stream().anyMatch(g -> "g2".equals(g.getId())));
    }

    @Test
    @DisplayName("A brand-new group from Git is added")
    void addsNewGroups() {
      List<ChannelGroup> existing = List.of(group("g1", "One"));
      Set<ChannelGroup> merged =
          PromotionService.mergeGroups(existing, Set.of(group("g9", "New")), Set.of());

      assertEquals(2, merged.size());
    }
  }

  @Nested
  @DisplayName("mergeLibraries / computeRemovedTemplateIds")
  class MergeLibrariesTests {

    private CodeTemplateLibrary library(String id, String name, String... templateIds) {
      CodeTemplateLibrary lib = new CodeTemplateLibrary();
      lib.setId(id);
      lib.setName(name);
      List<CodeTemplate> templates = new ArrayList<>();
      for (String templateId : templateIds) {
        templates.add(new CodeTemplate(templateId));
      }
      lib.setCodeTemplates(templates);
      return lib;
    }

    @Test
    @DisplayName("Unchanged existing libraries are preserved alongside updated ones")
    void preservesUnchangedLibraries() {
      List<CodeTemplateLibrary> existing =
          List.of(library("lib1", "One"), library("lib2", "Two"));
      List<CodeTemplateLibrary> updated = List.of(library("lib2", "Two v2"));

      List<CodeTemplateLibrary> merged =
          PromotionService.mergeLibraries(existing, updated, Set.of());

      assertEquals(2, merged.size(), "lib1 must survive a promotion that only touches lib2");
      assertTrue(merged.stream().anyMatch(
          l -> "lib2".equals(l.getId()) && "Two v2".equals(l.getName())));
    }

    @Test
    @DisplayName("Removed libraries are excluded and all their templates are removed")
    void removedLibraryRemovesItsTemplates() {
      List<CodeTemplateLibrary> existing =
          List.of(library("lib1", "One", "t1", "t2"), library("lib2", "Two", "t3"));

      List<CodeTemplateLibrary> merged =
          PromotionService.mergeLibraries(existing, List.of(), Set.of("lib1"));
      Set<String> removedTemplates =
          PromotionService.computeRemovedTemplateIds(existing, Set.of("lib1"), Map.of());

      assertEquals(1, merged.size());
      assertEquals(Set.of("t1", "t2"), removedTemplates);
    }

    @Test
    @DisplayName("Templates dropped from an updated library in Git are removed on the target")
    void droppedTemplatesAreRemoved() {
      List<CodeTemplateLibrary> existing = List.of(library("lib1", "One", "t1", "t2", "t3"));

      // Git now only carries t1 and t3 for lib1 — t2 was deleted in the PR.
      Set<String> removedTemplates = PromotionService.computeRemovedTemplateIds(
          existing, Set.of(), Map.of("lib1", Set.of("t1", "t3")));

      assertEquals(Set.of("t2"), removedTemplates);
    }

    @Test
    @DisplayName("Libraries not part of the promotion contribute no template removals")
    void untouchedLibrariesContributeNothing() {
      List<CodeTemplateLibrary> existing = List.of(library("lib1", "One", "t1"));

      Set<String> removedTemplates =
          PromotionService.computeRemovedTemplateIds(existing, Set.of(), Map.of());

      assertTrue(removedTemplates.isEmpty());
    }
  }

  // -----------------------------------------------------------------------
  // ChangedArtifacts
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("ChangedArtifacts")
  class ChangedArtifactsTests {

    @Test
    @DisplayName("isEmpty returns true when all sets empty and scripts unchanged")
    void emptyWhenAllEmpty() {
      PromotionService.ChangedArtifacts ca = new PromotionService.ChangedArtifacts(
          Set.of(), Set.of(), Set.of(), false);
      assertTrue(ca.isEmpty());
    }

    @Test
    @DisplayName("isEmpty returns false when channels present")
    void notEmptyWithChannels() {
      PromotionService.ChangedArtifacts ca = new PromotionService.ChangedArtifacts(
          Set.of("ch-1"), Set.of(), Set.of(), false);
      assertFalse(ca.isEmpty());
    }

    @Test
    @DisplayName("isEmpty returns false when global scripts changed")
    void notEmptyWithGlobalScripts() {
      PromotionService.ChangedArtifacts ca = new PromotionService.ChangedArtifacts(
          Set.of(), Set.of(), Set.of(), true);
      assertFalse(ca.isEmpty());
    }
  }

  // -----------------------------------------------------------------------
  // detectChanges — requires a real JGit repo
  // -----------------------------------------------------------------------

  @Nested
  @DisplayName("detectChanges")
  class DetectChangesTests {

    @Test
    @DisplayName("Detects changed channel and library IDs from diff")
    void detectsChangedIds(@TempDir Path workDir) throws Exception {
      // Set up a bare remote and clone it
      Path barePath = workDir.resolve("bare.git");
      String remoteUrl = seedBareRemote(barePath);

      Path clonePath = workDir.resolve("clone");
      GitRepoManager mgr = new GitRepoManager(clonePath);
      mgr.setBranch("main");
      mgr.init(remoteUrl);

      // Get the initial commit hash
      String initialCommit;
      try (Git git = Git.open(clonePath.toFile())) {
        initialCommit = git.log().setMaxCount(1).call().iterator().next().getName();
      }

      // Add channel and code template files, commit directly
      try (Git git = Git.open(clonePath.toFile())) {
        Path channelDir = clonePath.resolve("channels").resolve("ch-uuid-1");
        Files.createDirectories(channelDir);
        Files.writeString(channelDir.resolve("channel.xml"), "<channel/>", StandardCharsets.UTF_8);

        Path libDir = clonePath.resolve("code-templates").resolve("lib-uuid-1");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("library.xml"), "<lib/>", StandardCharsets.UTF_8);

        Path groupDir = clonePath.resolve("channel-groups");
        Files.createDirectories(groupDir);
        Files.writeString(groupDir.resolve("grp-uuid-1.xml"), "<group/>", StandardCharsets.UTF_8);

        Path scriptsDir = clonePath.resolve("global-scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(scriptsDir.resolve("deploy.js"), "// deploy", StandardCharsets.UTF_8);

        git.add().addFilepattern(".").call();
        git.commit().setMessage("add artefacts")
            .setAuthor("test", "test@example.com")
            .setSign(false).call();
      }

      String newCommit;
      try (Git git = Git.open(clonePath.toFile())) {
        newCommit = git.log().setMaxCount(1).call().iterator().next().getName();
      }

      PromotionService svc = new PromotionService(mgr, null);
      PromotionService.ChangedArtifacts changed = svc.detectChanges(initialCommit, newCommit);

      assertFalse(changed.isEmpty());
      assertTrue(changed.channelIds.contains("ch-uuid-1"));
      assertTrue(changed.libraryIds.contains("lib-uuid-1"));
      assertTrue(changed.channelGroupIds.contains("grp-uuid-1"));
      assertTrue(changed.globalScriptsChanged);
    }

    @Test
    @DisplayName("No changes when comparing same commit")
    void noChangesOnSameCommit(@TempDir Path workDir) throws Exception {
      Path barePath = workDir.resolve("bare.git");
      String remoteUrl = seedBareRemote(barePath);

      Path clonePath = workDir.resolve("clone");
      GitRepoManager mgr = new GitRepoManager(clonePath);
      mgr.setBranch("main");
      mgr.init(remoteUrl);

      String headCommit;
      try (Git git = Git.open(clonePath.toFile())) {
        headCommit = git.log().setMaxCount(1).call().iterator().next().getName();
      }

      PromotionService svc = new PromotionService(mgr, null);
      PromotionService.ChangedArtifacts changed = svc.detectChanges(headCommit, headCommit);

      assertTrue(changed.isEmpty());
    }

    @Test
    @DisplayName("Null fromCommit means diff from root — detects everything")
    void nullFromCommitDiffsFromRoot(@TempDir Path workDir) throws Exception {
      Path barePath = workDir.resolve("bare.git");
      String remoteUrl = seedBareRemote(barePath);

      Path clonePath = workDir.resolve("clone");
      GitRepoManager mgr = new GitRepoManager(clonePath);
      mgr.setBranch("main");
      mgr.init(remoteUrl);

      // Add a channel, commit
      try (Git git = Git.open(clonePath.toFile())) {
        Path channelDir = clonePath.resolve("channels").resolve("ch-1");
        Files.createDirectories(channelDir);
        Files.writeString(channelDir.resolve("channel.xml"), "<ch/>", StandardCharsets.UTF_8);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("add channel")
            .setAuthor("test", "test@example.com")
            .setSign(false).call();
      }

      String headCommit;
      try (Git git = Git.open(clonePath.toFile())) {
        headCommit = git.log().setMaxCount(1).call().iterator().next().getName();
      }

      PromotionService svc = new PromotionService(mgr, null);
      PromotionService.ChangedArtifacts changed = svc.detectChanges(null, headCommit);

      assertNotNull(changed);
      assertTrue(changed.channelIds.contains("ch-1"));
    }
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private static String seedBareRemote(Path barePath) throws IOException, GitAPIException {
    Path seedWork = Files.createTempDirectory("seed-work-");
    try (Git seed =
        Git.init().setDirectory(seedWork.toFile()).setInitialBranch("main").call()) {
      Files.writeString(seedWork.resolve("README.md"), "seed\n", StandardCharsets.UTF_8);
      seed.add().addFilepattern("README.md").call();
      seed.commit()
          .setMessage("seed")
          .setAuthor("seed", "seed@example.com")
          .setSign(false)
          .call();
      try (Git bare =
          Git.init().setBare(true).setDirectory(barePath.toFile()).setInitialBranch("main").call()) {
        // bare repo initialised
      }
      seed.remoteAdd()
          .setName("origin")
          .setUri(new org.eclipse.jgit.transport.URIish(barePath.toUri().toString()))
          .call();
      seed.push().setRemote("origin").add("main").call();
    } catch (java.net.URISyntaxException e) {
      throw new IOException(e);
    } finally {
      FileUtils.deleteRecursivelyQuietly(seedWork);
    }
    return barePath.toUri().toString();
  }
}
