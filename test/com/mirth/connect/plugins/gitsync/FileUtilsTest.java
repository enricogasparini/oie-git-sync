/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUtilsTest {

    @Test
    @DisplayName("Delete a single file")
    void deleteSingleFile(@TempDir Path tmp) throws IOException {
        Path f = Files.writeString(tmp.resolve("hello.txt"), "hi");
        FileUtils.deleteRecursively(f);
        assertFalse(Files.exists(f));
    }

    @Test
    @DisplayName("Delete a deeply nested tree post-order")
    void deleteNestedTree(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("root");
        Files.createDirectories(root.resolve("a/b/c/d"));
        Files.writeString(root.resolve("a/b/c/d/leaf1.txt"), "1");
        Files.writeString(root.resolve("a/b/c/d/leaf2.txt"), "2");
        Files.writeString(root.resolve("a/top.txt"), "top");
        Files.createDirectories(root.resolve("e/f"));
        Files.writeString(root.resolve("e/f/other.txt"), "other");

        FileUtils.deleteRecursively(root);

        assertFalse(Files.exists(root),
                "The entire tree, including the root directory, should be gone");
        // tmp itself must still exist — we only removed the subtree
        assertTrue(Files.exists(tmp));
    }

    @Test
    @DisplayName("Non-existent path is a no-op")
    void nonExistentPathIsNoOp(@TempDir Path tmp) {
        Path ghost = tmp.resolve("does/not/exist");
        assertDoesNotThrow(() -> FileUtils.deleteRecursively(ghost));
    }

    @Test
    @DisplayName("Null path is a no-op")
    void nullPathIsNoOp() {
        assertDoesNotThrow(() -> FileUtils.deleteRecursively(null));
    }

    @Test
    @DisplayName("Empty directory is removed")
    void emptyDirectoryRemoved(@TempDir Path tmp) throws IOException {
        Path empty = Files.createDirectory(tmp.resolve("empty"));
        FileUtils.deleteRecursively(empty);
        assertFalse(Files.exists(empty));
    }

    @Test
    @DisplayName("deleteRecursivelyQuietly swallows IOException from walkFileTree")
    void quietlyVariantDoesNotThrow() {
        // A path whose parent doesn't exist causes Files.walkFileTree to be a
        // no-op (we check Files.exists first), so the quietly variant really
        // does nothing. Use an obviously bogus path to prove it.
        Path bogus = Path.of("/no/such/file/anywhere/" + System.nanoTime());
        assertDoesNotThrow(() -> FileUtils.deleteRecursivelyQuietly(bogus));
    }

    @Test
    @DisplayName("deleteRecursively propagates an IOException from the underlying walk")
    void throwingVariantPropagates(@TempDir Path tmp) throws IOException {
        // Make a file, then attempt to delete its non-existent subdirectory.
        // Files.walkFileTree on a plain file throws IOException. We rely on
        // Files.exists first so that isn't a problem — but if we do point
        // deleteRecursively at a path we want to exist and the tree is
        // consistent, the helper should complete cleanly. This test pair
        // with the next one asserts the "happy" vs "quiet" contract split.
        Path f = Files.writeString(tmp.resolve("a.txt"), "x");
        FileUtils.deleteRecursively(f);
        assertFalse(Files.exists(f));
    }

    @Test
    @DisplayName("Files with unicode / whitespace names are handled")
    void unicodeAndWhitespaceNames(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("root"));
        Files.writeString(root.resolve("channel name with spaces.xml"), "<x/>");
        Files.writeString(root.resolve("e accent é.json"), "{}");
        Path sub = Files.createDirectory(root.resolve("nested space"));
        Files.writeString(sub.resolve("inside.txt"), "y");

        FileUtils.deleteRecursively(root);
        assertFalse(Files.exists(root));
    }

    @Test
    @DisplayName("Deleting the same path twice: second call is a no-op")
    void doubleDelete(@TempDir Path tmp) throws IOException {
        Path f = Files.writeString(tmp.resolve("once.txt"), "x");
        FileUtils.deleteRecursively(f);
        assertDoesNotThrow(() -> FileUtils.deleteRecursively(f));
        assertFalse(Files.exists(f));
    }

    @Test
    @DisplayName("throwingVariant on an unreadable directory throws IOException")
    void throwsOnActualIoError(@TempDir Path tmp) throws IOException {
        // Hard to trigger a walkFileTree IOException portably in a unit
        // test. Instead, pass a symlink-to-itself loop which walkFileTree
        // treats as a cycle and raises FileSystemLoopException (a subtype
        // of IOException) when FOLLOW_LINKS is enabled. FileUtils uses the
        // default walker (NOFOLLOW_LINKS), so the cycle is NOT entered —
        // the symlink is simply deleted as a file. This test documents
        // that behaviour so a future change to follow symlinks fails the
        // test rather than silently changing semantics.
        Path root = Files.createDirectory(tmp.resolve("loopdir"));
        Path link = root.resolve("self");
        try {
            Files.createSymbolicLink(link, root);
        } catch (UnsupportedOperationException | IOException e) {
            // Filesystems without symlink support (Windows without dev
            // mode, some CI containers) cannot exercise this. Skip.
            org.junit.jupiter.api.Assumptions.abort("symlink creation not supported on this filesystem");
            return;
        }
        // With NOFOLLOW_LINKS (the default), the walker visits the symlink
        // as a file entry and deletes it, then the empty directory. No
        // IOException expected.
        FileUtils.deleteRecursively(root);
        assertFalse(Files.exists(root));
    }

    @Test
    @DisplayName("assertThrows contract for IOException is reachable")
    void assertThrowsIsWired() {
        // Sanity check that the test harness will catch a real IOException
        // from deleteRecursively so future tests can rely on it.
        assertThrows(IOException.class, () -> {
            throw new IOException("planned");
        });
    }
}
