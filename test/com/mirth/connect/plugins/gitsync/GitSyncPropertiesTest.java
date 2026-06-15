/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GitSyncProperties is a constants-only class, so "testing" it means:
 *   (a) asserting the defaults have sane values so an accidental edit to a
 *       default (e.g. default branch becomes ""/null) fails loudly in CI, and
 *   (b) asserting every String-valued key constant is unique, so a
 *       copy-paste accident doesn't silently alias two different properties
 *       to the same key.
 *
 * The file itself is 60-odd lines of `public static final String X = "x"` so
 * the reflective approach scales better than writing one assertion per key.
 */
class GitSyncPropertiesTest {

    @Test
    @DisplayName("Default values are non-null and non-blank where appropriate")
    void defaultsAreSane() {
        assertAll(
                () -> assertEquals("main", GitSyncProperties.DEFAULT_BRANCH),
                () -> assertEquals("origin", GitSyncProperties.DEFAULT_REMOTE_NAME),
                () -> assertEquals("dev", GitSyncProperties.DEFAULT_ENVIRONMENT),
                () -> assertEquals("appdata/git-sync-repo", GitSyncProperties.DEFAULT_REPO_PATH),
                () -> assertEquals("OIE Git Sync", GitSyncProperties.DEFAULT_AUTHOR_NAME),
                () -> assertEquals("gitsync@oie.local", GitSyncProperties.DEFAULT_AUTHOR_EMAIL),
                () -> assertEquals("gitsync/{username}/{date}", GitSyncProperties.DEFAULT_COMMIT_BRANCH_PATTERN),
                () -> assertEquals(3, GitSyncProperties.DEFAULT_PUSH_RETRY_COUNT),
                () -> assertEquals(2000L, GitSyncProperties.DEFAULT_PUSH_RETRY_DELAY_MS));
    }

    @Test
    @DisplayName("Default branch pattern contains the username token")
    void branchPatternContainsRequiredToken() {
        // resolveBranchPattern() in GitSyncPlugin hard-depends on the
        // default having {username} in it, otherwise the sanitised branch
        // name for user "admin" falls back to a generic string.
        assertTrue(GitSyncProperties.DEFAULT_COMMIT_BRANCH_PATTERN.contains("{username}"));
    }

    @Test
    @DisplayName("All String property-key constants are unique")
    void propertyKeysAreUnique() throws IllegalAccessException {
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Field f : GitSyncProperties.class.getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers())
                    || !Modifier.isStatic(f.getModifiers())
                    || !Modifier.isFinal(f.getModifiers())) {
                continue;
            }
            if (f.getType() != String.class) {
                continue;
            }
            if (f.getName().startsWith("DEFAULT_")) {
                // Default values are not property keys; many will collide
                // with the key constants by design (e.g. key BRANCH = "branch"
                // and DEFAULT_BRANCH = "main", but DEFAULT_REMOTE_NAME =
                // "origin" also happens to match no key — skipping the whole
                // DEFAULT_ group keeps the intent clear).
                continue;
            }
            String value = (String) f.get(null);
            assertNotNull(value, "property key " + f.getName() + " must not be null");
            assertFalse(value.isEmpty(), "property key " + f.getName() + " must not be empty");
            if (!seen.add(value)) {
                duplicates.add(f.getName() + "=" + value);
            }
        }
        assertTrue(duplicates.isEmpty(),
                "Duplicate property key constants would alias in Properties: " + duplicates);
    }

    @Test
    @DisplayName("GitSyncProperties cannot be instantiated")
    void isUtilityClass() throws NoSuchMethodException {
        var ctor = GitSyncProperties.class.getDeclaredConstructor();
        assertFalse(Modifier.isPublic(ctor.getModifiers()),
                "The implicit constructor should not be public — this is a utility class.");
    }
}
