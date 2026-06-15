/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.plugins.gitsync.model.PendingChange;
import com.mirth.connect.plugins.gitsync.model.PendingChangeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingChangeTrackerTest {

    @TempDir
    Path repoRoot;

    PendingChangeTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new PendingChangeTracker(repoRoot);
    }

    private static PendingChange modifyChannel(String id, String name, int revision) {
        return new PendingChange(PendingChange.Type.CHANNEL, id, name,
                PendingChange.Action.MODIFY, revision);
    }

    private static PendingChange modifyLibrary(String id, String name, int revision) {
        return new PendingChange(PendingChange.Type.CODE_TEMPLATE_LIBRARY, id, name,
                PendingChange.Action.MODIFY, revision);
    }

    @Nested
    @DisplayName("Empty state")
    class EmptyState {

        @Test
        void getPendingForUnknownUserReturnsEmptyList() throws Exception {
            PendingChangeList list = tracker.getPending("never-seen");
            assertTrue(list.isEmpty());
            assertEquals("never-seen", list.getUsername());
            assertEquals(0, list.size());
        }

        @Test
        void getUsersWithPendingIsEmptyByDefault() throws Exception {
            assertTrue(tracker.getUsersWithPending().isEmpty());
        }

        @Test
        void clearPendingForUnknownUserIsNoOp() throws Exception {
            // Should not throw if the user's directory doesn't exist.
            assertTrue(tracker.getUsersWithPending().isEmpty());
            // clearPending doesn't return anything — just assert it doesn't blow up
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> tracker.clearPending("phantom"));
        }
    }

    @Nested
    @DisplayName("recordModify()")
    class RecordModify {

        @Test
        void firstChangeCreatesManifestFile() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "Channel One", 1));

            Path manifest = repoRoot.resolve(".gitsync-pending/alice/manifest.json");
            assertTrue(Files.exists(manifest), "manifest.json should exist after recordModify");

            // Parse via Jackson so we assert actual JSON shape, not text substring.
            JsonNode root = new ObjectMapper().readTree(
                    Files.readString(manifest, StandardCharsets.UTF_8));
            assertEquals("alice", root.get("username").asText());
            assertNotNull(root.get("updated"));
            JsonNode changes = root.get("changes");
            assertEquals(1, changes.size());
            JsonNode change = changes.get(0);
            assertAll(
                    () -> assertEquals("CHANNEL", change.get("type").asText()),
                    () -> assertEquals("ch-1", change.get("id").asText()),
                    () -> assertEquals("Channel One", change.get("name").asText()),
                    () -> assertEquals("MODIFY", change.get("action").asText()),
                    () -> assertEquals(1, change.get("revision").asInt()));
        }

        @Test
        void recordingTheSameChannelTwiceUpsertsRatherThanDuplicates() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "Channel One", 1));
            tracker.recordModify("alice", modifyChannel("ch-1", "Channel One (renamed)", 2));

            PendingChangeList list = tracker.getPending("alice");
            assertEquals(1, list.size(), "Two saves of the same channel must not produce two entries");
            PendingChange c = list.getChanges().get(0);
            assertEquals("Channel One (renamed)", c.getName());
            assertEquals(2, c.getRevision(), "Revision from the second save must win");
        }

        @Test
        void recordingDifferentArtefactsAppends() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "Channel", 1));
            tracker.recordModify("alice", modifyLibrary("lib-1", "Library", 1));
            tracker.recordModify("alice", modifyChannel("ch-2", "Other Channel", 1));

            PendingChangeList list = tracker.getPending("alice");
            assertEquals(3, list.size());
        }

        @Test
        void managesSeparateManifestsPerUser() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-a", "A", 1));
            tracker.recordModify("bob", modifyChannel("ch-b", "B", 1));

            assertEquals(1, tracker.getPending("alice").size());
            assertEquals(1, tracker.getPending("bob").size());
            assertTrue(tracker.getUsersWithPending().containsAll(List.of("alice", "bob")));
        }

        @Test
        void crossUserReconciliationStealsEntriesFromOtherUsers() throws Exception {
            // Alice saves ch-1 first. Bob then saves ch-1 (the same channel).
            // The entry must move from alice's manifest to bob's, not live in
            // both — that way whoever saved last is the one whose commit
            // overwrites main when they click "Commit to Git".
            tracker.recordModify("alice", modifyChannel("ch-1", "by-alice", 1));
            tracker.recordModify("bob", modifyChannel("ch-1", "by-bob", 2));

            PendingChangeList alices = tracker.getPending("alice");
            PendingChangeList bobs = tracker.getPending("bob");
            assertTrue(alices.isEmpty(), "alice should no longer hold ch-1");
            assertEquals(1, bobs.size());
            assertEquals("by-bob", bobs.getChanges().get(0).getName());
            assertEquals(2, bobs.getChanges().get(0).getRevision());
        }

        @Test
        void sameIdDifferentTypeCoexists() throws Exception {
            // Same id, different Type (CHANNEL vs CODE_TEMPLATE_LIBRARY) means
            // two different entities — they must both be kept, not collapsed.
            tracker.recordModify("alice", modifyChannel("same-id", "a channel", 1));
            tracker.recordModify("alice", modifyLibrary("same-id", "a library", 1));

            PendingChangeList list = tracker.getPending("alice");
            assertEquals(2, list.size());
        }
    }

    @Nested
    @DisplayName("recordDelete()")
    class RecordDelete {

        @Test
        void deletingACommittedArtefactRecordsDELETEAction() throws Exception {
            tracker.recordDelete("alice", PendingChange.Type.CHANNEL, "ch-1", "gone", true);

            PendingChangeList list = tracker.getPending("alice");
            assertEquals(1, list.size());
            PendingChange c = list.getChanges().get(0);
            assertEquals(PendingChange.Action.DELETE, c.getAction());
            assertEquals("ch-1", c.getId());
            assertEquals("gone", c.getName());
        }

        @Test
        void deletingAnUncommittedArtefactDoesNotRecordAnything() throws Exception {
            // If the artefact was never in a previous commit, there's nothing
            // to `git rm` — the delete is a local-only cancellation and
            // produces no manifest entry at all.
            tracker.recordDelete("alice", PendingChange.Type.CHANNEL, "ch-1", "gone", false);

            PendingChangeList list = tracker.getPending("alice");
            assertTrue(list.isEmpty());
        }

        @Test
        void deletingAnArtefactWithPendingMODIFYCancelsTheModify() throws Exception {
            // User saved ch-1 (MODIFY) then deleted it. The pending MODIFY
            // entry must be removed so the next commit doesn't try to save
            // and then delete in the same pass.
            tracker.recordModify("alice", modifyChannel("ch-1", "about to delete", 1));
            tracker.recordDelete("alice", PendingChange.Type.CHANNEL, "ch-1", "about to delete", true);

            PendingChangeList list = tracker.getPending("alice");
            assertEquals(1, list.size());
            assertEquals(PendingChange.Action.DELETE, list.getChanges().get(0).getAction(),
                    "Only the DELETE entry should remain");
        }

        @Test
        void deletingRemovesPendingArtefactFilesInUserDir() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "X", 1));
            // Simulate the save hook having written the channel files into
            // alice's pending dir (what GitSyncPlugin does on a real save).
            Path artefactDir = repoRoot.resolve(".gitsync-pending/alice/channels/ch-1");
            Files.createDirectories(artefactDir);
            Files.writeString(artefactDir.resolve("channel.xml"), "<x/>");

            tracker.recordDelete("alice", PendingChange.Type.CHANNEL, "ch-1", "X", true);

            // The pending artefact files should have been deleted from
            // alice's pending dir as part of recordDelete — even if the
            // manifest retains a DELETE entry, there's no point keeping the
            // file copy.
            assertFalse(Files.exists(artefactDir),
                    "Pending artefact files for the deleted channel should be cleaned up");
        }

        @Test
        void crossUserReconciliationOnDelete() throws Exception {
            // Alice has ch-1 pending. Bob deletes ch-1. The entry in alice's
            // pending list must be cleared, same as for a modify.
            tracker.recordModify("alice", modifyChannel("ch-1", "alice", 1));
            tracker.recordDelete("bob", PendingChange.Type.CHANNEL, "ch-1", "by-bob", true);

            PendingChangeList alices = tracker.getPending("alice");
            PendingChangeList bobs = tracker.getPending("bob");
            assertTrue(alices.isEmpty());
            assertEquals(1, bobs.size());
            assertEquals(PendingChange.Action.DELETE, bobs.getChanges().get(0).getAction());
        }
    }

    @Nested
    @DisplayName("Persistence / round-trip")
    class Persistence {

        @Test
        void manifestIsPersistedAndReadableByANewTrackerInstance() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "Channel", 3));
            tracker.recordModify("alice", modifyLibrary("lib-1", "Library", 2));

            // A fresh tracker pointing at the same repo root must see the
            // persisted manifest.
            PendingChangeTracker fresh = new PendingChangeTracker(repoRoot);
            PendingChangeList list = fresh.getPending("alice");
            assertEquals(2, list.size());
            // Changes are sorted by type then id for deterministic diff output.
            assertEquals("ch-1", list.getChanges().get(0).getId());
            assertEquals("lib-1", list.getChanges().get(1).getId());
        }

        @Test
        void corruptedManifestIsTreatedAsEmpty() throws Exception {
            Path manifest = repoRoot.resolve(".gitsync-pending/broken/manifest.json");
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, "this is not JSON at all", StandardCharsets.UTF_8);

            PendingChangeList list = tracker.getPending("broken");
            assertTrue(list.isEmpty(),
                    "A corrupt manifest must not crash getPending — starts fresh instead");
            assertEquals("broken", list.getUsername());
        }

        @Test
        void manifestWritesAreAtomicViaTmpThenRename() throws Exception {
            // Implementation detail: the tracker writes to manifest.json.tmp
            // then moves to manifest.json with ATOMIC_MOVE. Assert that after
            // a recordModify no .tmp file is left behind.
            tracker.recordModify("alice", modifyChannel("ch-1", "C", 1));
            Path tmp = repoRoot.resolve(".gitsync-pending/alice/manifest.json.tmp");
            assertFalse(Files.exists(tmp), "No leftover .tmp file after a successful write");
        }
    }

    @Nested
    @DisplayName("clearPending()")
    class ClearPending {

        @Test
        void deletesUserDirectoryAndAllContents() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "C", 1));
            // Create some extra junk files under the user's pending dir — they
            // should all get swept.
            Path userDir = repoRoot.resolve(".gitsync-pending/alice");
            Files.writeString(userDir.resolve("stray.txt"), "leftover");
            Files.createDirectories(userDir.resolve("channels/ch-1"));
            Files.writeString(userDir.resolve("channels/ch-1/channel.xml"), "<x/>");

            tracker.clearPending("alice");

            assertFalse(Files.exists(userDir));
            assertTrue(tracker.getPending("alice").isEmpty());
            assertFalse(tracker.getUsersWithPending().contains("alice"));
        }
    }

    @Nested
    @DisplayName("recordModify() with writer callback")
    class RecordModifyWithWriter {

        @Test
        void writerRunsAgainstTheUserDirBeforeTheManifestIsSaved() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "C", 1), userDir -> {
                Path channelDir = userDir.resolve("channels/ch-1");
                Files.createDirectories(channelDir);
                Files.writeString(channelDir.resolve("channel.xml"), "<channel/>");
            });

            Path target = repoRoot.resolve(".gitsync-pending/alice/channels/ch-1/channel.xml");
            assertTrue(Files.exists(target), "Writer output should land in the user's pending dir");
            assertEquals(1, tracker.getPending("alice").size());
        }

        @Test
        void writerFailurePropagatesAndLeavesNoManifestEntry() throws Exception {
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> tracker.recordModify("alice", modifyChannel("ch-1", "C", 1), userDir -> {
                        throw new java.io.IOException("disk full");
                    }));

            assertTrue(tracker.getPending("alice").isEmpty(),
                    "A failed write must not record a pending entry pointing at missing files");
        }
    }

    @Nested
    @DisplayName("clearPending(username, committed)")
    class SelectiveClearPending {

        @Test
        void removesOnlyTheCommittedChangesAndTheirFiles() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "Committed", 1), userDir -> {
                Path dir = userDir.resolve("channels/ch-1");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("channel.xml"), "<x/>");
            });
            // Snapshot what would be flushed by "Commit to Git" ...
            List<PendingChange> snapshot = List.copyOf(tracker.getPending("alice").getChanges());
            // ... then a save hook fires while the Git operations are in flight.
            tracker.recordModify("alice", modifyChannel("ch-2", "Late save", 1), userDir -> {
                Path dir = userDir.resolve("channels/ch-2");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("channel.xml"), "<y/>");
            });

            tracker.clearPending("alice", snapshot);

            PendingChangeList remaining = tracker.getPending("alice");
            assertEquals(1, remaining.size(),
                    "The change recorded mid-commit must survive the post-commit clear");
            assertEquals("ch-2", remaining.getChanges().get(0).getId());
            assertFalse(Files.exists(
                    repoRoot.resolve(".gitsync-pending/alice/channels/ch-1")),
                    "Committed artefact files should be cleaned up");
            assertTrue(Files.exists(
                    repoRoot.resolve(".gitsync-pending/alice/channels/ch-2/channel.xml")),
                    "Uncommitted artefact files must be preserved");
        }

        @Test
        void clearingEverythingRemovesTheUserDirectory() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "C", 1));
            List<PendingChange> snapshot = List.copyOf(tracker.getPending("alice").getChanges());

            tracker.clearPending("alice", snapshot);

            assertFalse(Files.exists(repoRoot.resolve(".gitsync-pending/alice")),
                    "An empty manifest should remove the whole user directory");
            assertFalse(tracker.getUsersWithPending().contains("alice"));
        }

        @Test
        void aReRecordedChangeOfTheSameActionIsConsumedBySnapshotClear() throws Exception {
            // Same artefact saved again mid-commit: last-save-wins semantics
            // mean the snapshot's MODIFY and the late MODIFY are the same
            // logical entry, so the clear consumes it. The newer file content
            // was already flushed by the in-flight commit's file callback or
            // will be re-saved by the user; either way no stale entry remains.
            tracker.recordModify("alice", modifyChannel("ch-1", "v1", 1));
            List<PendingChange> snapshot = List.copyOf(tracker.getPending("alice").getChanges());
            tracker.recordModify("alice", modifyChannel("ch-1", "v2", 2));

            tracker.clearPending("alice", snapshot);

            assertTrue(tracker.getPending("alice").isEmpty());
        }
    }

    @Nested
    @DisplayName("Username sanitisation")
    class UsernameSanitisation {

        @Test
        void slashesAndOtherUnsafeCharactersAreReplaced() throws Exception {
            // Username sanitisation prevents a malicious or mistyped OIE
            // username like "alice/../bob" from escaping the .gitsync-pending
            // directory. The contract is that no path separator can survive
            // in the sanitised name — a substring "xx" or "_.._" in a single
            // path component is harmless because the filesystem only treats
            // "..", not "_.._", as a parent-directory token. The sanitiser
            // intentionally allows "." so names like "alice.bob" round-trip.
            tracker.recordModify("alice/../bob", modifyChannel("ch-1", "C", 1));

            // Directory "alice" must not have been created as a traversal side
            // effect.
            assertFalse(Files.isDirectory(repoRoot.resolve(".gitsync-pending/alice")),
                    "Unsanitised path components must not create real directories");
            List<String> users = tracker.getUsersWithPending();
            assertEquals(1, users.size());
            String sanitised = users.get(0);
            assertFalse(sanitised.contains("/"),
                    "Sanitised username must not contain forward slashes");
            assertFalse(sanitised.contains("\\"),
                    "Sanitised username must not contain backslashes");
            // And the resolved path actually sits inside .gitsync-pending/,
            // which is the property we really care about.
            Path resolved = tracker.getUserDir("alice/../bob").toAbsolutePath().normalize();
            Path pendingRoot =
                    repoRoot.resolve(".gitsync-pending").toAbsolutePath().normalize();
            assertTrue(resolved.startsWith(pendingRoot),
                    "The resolved user directory must stay inside .gitsync-pending");
        }

        @Test
        void nullOrBlankUsernameBecomesUnknown() throws Exception {
            // PendingChangeTracker.sanitiseUsername falls back to "unknown"
            // for null/blank input so we still produce a valid path.
            tracker.recordModify(null, modifyChannel("ch-1", "C", 1));
            List<String> users = tracker.getUsersWithPending();
            assertEquals(1, users.size());
            assertEquals("unknown", users.get(0));
        }

        @Test
        void normalUsernameIsUnchanged() throws Exception {
            tracker.recordModify("alice", modifyChannel("ch-1", "C", 1));
            tracker.recordModify("bob_123", modifyChannel("ch-2", "C", 1));
            tracker.recordModify("Alice.B", modifyChannel("ch-3", "C", 1));

            List<String> users = tracker.getUsersWithPending();
            assertTrue(users.contains("alice"));
            assertTrue(users.contains("bob_123"));
            assertTrue(users.contains("Alice.B"));
        }

        @Test
        void dotOnlyUsernamesCannotEscapeThePendingDirectory() throws Exception {
            // "." and ".." survive a pure character-class filter because dots
            // are legal in usernames — but as a whole path component they
            // resolve to the pending dir itself or the repo root. A username
            // of ".." would otherwise turn clearPending into a recursive
            // delete of the entire local clone.
            for (String hostile : new String[] {".", "..", "..."}) {
                assertEquals("unknown", PendingChangeTracker.sanitiseUsername(hostile),
                        "Dot-only username '" + hostile + "' must be neutralised");
                Path resolved = tracker.getUserDir(hostile).toAbsolutePath().normalize();
                Path pendingRoot =
                        repoRoot.resolve(".gitsync-pending").toAbsolutePath().normalize();
                assertTrue(resolved.startsWith(pendingRoot) && !resolved.equals(pendingRoot),
                        "Resolved dir for '" + hostile + "' must be strictly inside the pending dir");
            }
        }

        @Test
        void clearPendingWithTraversalUsernameDoesNotDeleteTheRepo() throws Exception {
            Files.writeString(repoRoot.resolve("precious.txt"), "do not delete");

            tracker.clearPending("..");

            assertTrue(Files.exists(repoRoot.resolve("precious.txt")),
                    "clearPending('..') must not delete files outside .gitsync-pending");
        }
    }

    @Nested
    @DisplayName("getUserDir()")
    class GetUserDir {

        @Test
        void returnsThePathUnderGitsyncPendingForTheSanitisedUsername() {
            Path dir = tracker.getUserDir("alice");
            assertEquals(repoRoot.resolve(".gitsync-pending/alice"), dir);
        }

        @Test
        void sanitisesUnsafeCharacters() {
            Path dir = tracker.getUserDir("weird/name");
            // Path should still be inside .gitsync-pending/ (i.e., does not
            // escape via /).
            assertTrue(dir.startsWith(repoRoot.resolve(".gitsync-pending")));
        }
    }
}
