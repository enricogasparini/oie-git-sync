/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Small filesystem helpers shared across the plugin. */
public final class FileUtils {

  private static final Logger logger = LogManager.getLogger(FileUtils.class);

  private FileUtils() {}

  /**
   * Recursively deletes a file or directory tree. No-op if the path does not exist.
   *
   * <p>Uses {@link Files#walkFileTree} which visits files before their parent directory, so
   * deletion order is correct without relying on stream ordering. Any individual delete failure is
   * rethrown, because partial deletion is almost always a bug the caller needs to see.
   */
  public static void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
              throw exc;
            }
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Best-effort recursive delete: logs failures instead of throwing. Use only in cleanup paths
   * where partial success is acceptable (e.g. during a reset where we immediately re-clone
   * afterwards).
   */
  public static void deleteRecursivelyQuietly(Path root) {
    try {
      deleteRecursively(root);
    } catch (IOException e) {
      logger.warn("Best-effort recursive delete of {} failed: {}", root, e.getMessage());
    }
  }
}
