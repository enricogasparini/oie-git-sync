/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mirth.connect.plugins.gitsync.model.PendingChange;
import com.mirth.connect.plugins.gitsync.model.PendingChange.Action;
import com.mirth.connect.plugins.gitsync.model.PendingChange.Type;
import com.mirth.connect.plugins.gitsync.model.PendingChangeList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tracks per-user pending changes in the local Git repo.
 *
 * <p>Each user has their own directory at <repoRoot>/.gitsync-pending/<username>/ containing a
 * manifest.json and copies of the serialised artefacts they've touched.
 *
 * <p>Save hooks write here instead of committing to Git directly. The "Commit to Git" action reads
 * a user's manifest, copies files into the main working tree, commits to their feature branch, and
 * clears the pending directory.
 */
public class PendingChangeTracker {

  private static final Logger logger = LogManager.getLogger(PendingChangeTracker.class);
  private static final String PENDING_DIR = ".gitsync-pending";
  private static final String MANIFEST_FILE = "manifest.json";

  /** ObjectMapper configured once — thread-safe for serialise/parse after construction. */
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private static final ObjectWriter PRETTY_WRITER = MAPPER.writerWithDefaultPrettyPrinter();

  private final Path repoPath;
  private final ReentrantLock lock = new ReentrantLock(true);

  public PendingChangeTracker(Path repoPath) {
    this.repoPath = repoPath;
  }

  /**
   * Records a MODIFY change for the given user. The caller is responsible for writing the artefact
   * files into the user's pending directory before calling this (or it can be done together via
   * recordModify with a writer callback).
   *
   * <p>Also removes any existing entry for the same artefact from other users' manifests (OIE's
   * last-save-wins semantics).
   */
  public void recordModify(String username, PendingChange change) throws IOException {
    recordModify(username, change, null);
  }

  /**
   * Records a MODIFY change, optionally writing the artefact files first via the given writer. The
   * writer runs inside the tracker lock so a concurrent {@link #removeFromAllUsers} or {@link
   * #clearPending} cannot delete the files between the write and the manifest update.
   */
  public void recordModify(String username, PendingChange change, ArtefactWriter writer)
      throws IOException {
    lock.lock();
    try {
      removeFromAllUsers(change.getType(), change.getId(), username);

      if (writer != null) {
        writer.write(userDir(username));
      }

      PendingChangeList list = loadManifest(username);
      upsert(list, change);
      saveManifest(username, list);

      logger.debug(
          "Recorded pending {} {} for user {}", change.getType(), change.getAction(), username);
    } finally {
      lock.unlock();
    }
  }

  /** Callback that writes artefact files into the user's pending directory under the lock. */
  @FunctionalInterface
  public interface ArtefactWriter {
    void write(Path userDir) throws IOException;
  }

  /**
   * Records a DELETE change. If the artefact was only in the user's pending directory (never
   * committed), this just removes the pending entry and files. Otherwise it marks it as a pending
   * deletion so the next commit removes it from the working tree.
   *
   * @param wasCommitted true if the artefact existed in a previous committed state
   */
  public void recordDelete(String username, Type type, String id, String name, boolean wasCommitted)
      throws IOException {
    lock.lock();
    try {
      Path userDir = userDir(username);
      PendingChangeList list = loadManifest(username);

      // Remove any pending MODIFY for this artefact (cancels out)
      list.getChanges().removeIf(c -> c.getType() == type && id.equals(c.getId()));

      // Delete any pending artefact files for it
      deleteArtefactFiles(userDir, type, id);

      if (wasCommitted) {
        // Need to record a DELETE so the commit does a git rm
        PendingChange deleteChange = new PendingChange(type, id, name, Action.DELETE, 0);
        list.getChanges().add(deleteChange);
      }

      saveManifest(username, list);

      // Also remove from other users' pending (can't edit something that's been deleted)
      removeFromAllUsers(type, id, username);

      logger.debug(
          "Recorded pending DELETE of {} {} for user {} (wasCommitted={})",
          type,
          id,
          username,
          wasCommitted);
    } finally {
      lock.unlock();
    }
  }

  /** Returns the pending change list for a user. */
  public PendingChangeList getPending(String username) throws IOException {
    lock.lock();
    try {
      return loadManifest(username);
    } finally {
      lock.unlock();
    }
  }

  /** Clears a user's entire pending directory and manifest. Used by "Discard All". */
  public void clearPending(String username) throws IOException {
    lock.lock();
    try {
      Path userDir = userDir(username);
      if (Files.exists(userDir)) {
        FileUtils.deleteRecursively(userDir);
      }
      logger.debug("Cleared pending directory for user {}", LogSanitiser.clean(username));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Clears only the given committed changes from a user's pending set. Called after a successful
   * commit with the snapshot of changes that were actually committed — any change recorded while
   * the (potentially slow) Git operation was in flight is preserved rather than silently wiped.
   */
  public void clearPending(String username, List<PendingChange> committed) throws IOException {
    lock.lock();
    try {
      Path userDir = userDir(username);
      PendingChangeList list = loadManifest(username);

      for (PendingChange done : committed) {
        boolean removed =
            list.getChanges()
                .removeIf(
                    c ->
                        c.getType() == done.getType()
                            && done.getId().equals(c.getId())
                            && c.getAction() == done.getAction());
        if (removed && done.getAction() != Action.DELETE) {
          deleteArtefactFiles(userDir, done.getType(), done.getId());
        }
      }

      if (list.isEmpty()) {
        if (Files.exists(userDir)) {
          FileUtils.deleteRecursively(userDir);
        }
      } else {
        saveManifest(username, list);
        logger.info(
            "{} pending change(s) recorded during the commit were preserved for user {}",
            list.size(),
            LogSanitiser.clean(username));
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the filesystem path to a user's pending directory. The caller can write files here
   * before calling recordModify.
   */
  public Path getUserDir(String username) {
    return userDir(username);
  }

  /** Returns the list of users that currently have pending changes. */
  public List<String> getUsersWithPending() throws IOException {
    lock.lock();
    try {
      Path pendingDir = repoPath.resolve(PENDING_DIR);
      if (!Files.exists(pendingDir)) {
        return Collections.emptyList();
      }
      List<String> users = new ArrayList<>();
      try (Stream<Path> stream = Files.list(pendingDir)) {
        stream.filter(Files::isDirectory).forEach(p -> users.add(p.getFileName().toString()));
      }
      return users;
    } finally {
      lock.unlock();
    }
  }

  // -----------------------------------------------------------------------
  // Internal helpers
  // -----------------------------------------------------------------------

  private Path userDir(String username) {
    Path pendingDir = repoPath.resolve(PENDING_DIR).normalize();
    Path dir = pendingDir.resolve(sanitiseUsername(username)).normalize();
    // Belt-and-braces: the sanitiser should make traversal impossible, but never hand out a path
    // outside the pending directory regardless.
    if (dir.equals(pendingDir) || !dir.startsWith(pendingDir)) {
      throw new IllegalArgumentException("Invalid username for pending directory");
    }
    return dir;
  }

  static String sanitiseUsername(String username) {
    if (username == null || username.isBlank()) {
      return "unknown";
    }
    String sanitised = username.replaceAll("[^a-zA-Z0-9._-]", "_");
    // "." and ".." survive the character filter but resolve to the pending dir itself or the repo
    // root — a username of ".." would otherwise let pending operations escape the pending dir.
    if (sanitised.replace(".", "").isEmpty()) {
      return "unknown";
    }
    return sanitised;
  }

  private PendingChangeList loadManifest(String username) throws IOException {
    Path manifestPath = userDir(username).resolve(MANIFEST_FILE);
    if (!Files.exists(manifestPath)) {
      return new PendingChangeList(username);
    }
    try {
      String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
      PendingChangeList list = MAPPER.readValue(json, PendingChangeList.class);
      if (list.getUsername() == null || list.getUsername().isBlank()) {
        list.setUsername(username);
      }
      return list;
    } catch (JsonProcessingException e) {
      logger.warn(
          "Failed to parse manifest for user {}, starting fresh", LogSanitiser.clean(username), e);
      return new PendingChangeList(username);
    }
  }

  private void saveManifest(String username, PendingChangeList list) throws IOException {
    Files.createDirectories(userDir(username));
    list.setUsername(username);
    list.setUpdated(Instant.now().toString());
    // Sort changes by type then id for stable output
    list.getChanges()
        .sort(
            Comparator.comparing((PendingChange c) -> c.getType().toString())
                .thenComparing(PendingChange::getId));
    String json = PRETTY_WRITER.writeValueAsString(list);
    Path target = userDir(username).resolve(MANIFEST_FILE);
    Path tmp = target.resolveSibling(MANIFEST_FILE + ".tmp");
    Files.writeString(tmp, json + "\n", StandardCharsets.UTF_8);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private void upsert(PendingChangeList list, PendingChange change) {
    list.getChanges()
        .removeIf(c -> c.getType() == change.getType() && change.getId().equals(c.getId()));
    list.getChanges().add(change);
  }

  private void removeFromAllUsers(Type type, String id, String exceptUsername) throws IOException {
    Path pendingDir = repoPath.resolve(PENDING_DIR);
    if (!Files.exists(pendingDir)) {
      return;
    }
    try (Stream<Path> stream = Files.list(pendingDir)) {
      List<Path> userDirs = stream.filter(Files::isDirectory).toList();
      for (Path dir : userDirs) {
        String username = dir.getFileName().toString();
        if (username.equals(sanitiseUsername(exceptUsername))) {
          continue;
        }
        PendingChangeList list = loadManifest(username);
        boolean removed =
            list.getChanges().removeIf(c -> c.getType() == type && id.equals(c.getId()));
        if (removed) {
          deleteArtefactFiles(dir, type, id);
          saveManifest(username, list);
          logger.debug("Removed {} {} from user {}'s pending (reassigned)", type, id, username);
        }
      }
    }
  }

  private void deleteArtefactFiles(Path userDir, Type type, String id) throws IOException {
    String safeId = ArtifactSerializer.requireSafeId(id);
    Path artefactDir =
        switch (type) {
          case CHANNEL -> userDir.resolve("channels").resolve(safeId);
          case CODE_TEMPLATE_LIBRARY -> userDir.resolve("code-templates").resolve(safeId);
        };
    if (Files.exists(artefactDir)) {
      FileUtils.deleteRecursively(artefactDir);
    }
  }
}
