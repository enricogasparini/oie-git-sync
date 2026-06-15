/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the simple DTOs in the model package — GitSyncStatus, SyncRecord,
 * PromotionRequest, PromotionResult, PendingChange, PendingChangeList.
 *
 * These are plain POJOs used by XStream for HTTP payload serialisation and
 * by Jackson for the pending-change manifest. Testing them directly is
 * worthwhile mostly for three reasons:
 *   (1) the static factory methods on SyncRecord have conditional logic
 *       (message construction) that would silently drift if nobody tested it,
 *   (2) the status "record success" / "record failure" counters are state
 *       mutations where a bug would shadow the real count in the admin UI,
 *   (3) the addXxx methods on PromotionResult build up lists that the caller
 *       reads back.
 */
class ModelDtoRoundTripTest {

    @Nested
    @DisplayName("GitSyncStatus")
    class GitSyncStatusTests {

        @Test
        void gettersAndSettersRoundTrip() {
            GitSyncStatus status = new GitSyncStatus();
            Instant now = Instant.now();
            status.setEnabled(true);
            status.setRepoInitialised(true);
            status.setBranch("main");
            status.setLastCommitHash("abc123");
            status.setLastSyncTime(now);
            status.setLastError("nope");
            status.setLastErrorTime(now);
            status.setTotalSyncs(5);
            status.setTotalErrors(1);
            status.setEnvironmentName("dev");
            status.setNodeRole("BOTH");
            status.setPendingChangeCount(3);

            assertAll(
                    () -> assertTrue(status.isEnabled()),
                    () -> assertTrue(status.isRepoInitialised()),
                    () -> assertEquals("main", status.getBranch()),
                    () -> assertEquals("abc123", status.getLastCommitHash()),
                    () -> assertEquals(now, status.getLastSyncTime()),
                    () -> assertEquals("nope", status.getLastError()),
                    () -> assertEquals(now, status.getLastErrorTime()),
                    () -> assertEquals(5, status.getTotalSyncs()),
                    () -> assertEquals(1, status.getTotalErrors()),
                    () -> assertEquals("dev", status.getEnvironmentName()),
                    () -> assertEquals("BOTH", status.getNodeRole()),
                    () -> assertEquals(3, status.getPendingChangeCount()),
                    () -> assertNotNull(status.getRecentRecords()));
        }

        @Test
        void recordSuccessUpdatesHashSyncTimeAndIncrementsTotal() {
            GitSyncStatus status = new GitSyncStatus();
            assertEquals(0, status.getTotalSyncs());
            status.recordSuccess("deadbeef");
            assertEquals(1, status.getTotalSyncs());
            assertEquals("deadbeef", status.getLastCommitHash());
            assertNotNull(status.getLastSyncTime());

            status.recordSuccess("feedface");
            assertEquals(2, status.getTotalSyncs());
            assertEquals("feedface", status.getLastCommitHash());
        }

        @Test
        void recordFailureUpdatesErrorAndIncrementsCounter() {
            GitSyncStatus status = new GitSyncStatus();
            assertEquals(0, status.getTotalErrors());
            status.recordFailure("something blew up");
            assertEquals(1, status.getTotalErrors());
            assertEquals("something blew up", status.getLastError());
            assertNotNull(status.getLastErrorTime());

            status.recordFailure("again");
            assertEquals(2, status.getTotalErrors());
            assertEquals("again", status.getLastError());
        }

        @Test
        void setRecentRecordsReplacesTheList() {
            GitSyncStatus status = new GitSyncStatus();
            SyncRecord r = SyncRecord.success(
                    SyncRecord.ArtifactType.CHANNEL, SyncRecord.Action.SAVE,
                    "id", "name", "hash");
            status.setRecentRecords(List.of(r));
            assertEquals(1, status.getRecentRecords().size());
            assertEquals(r, status.getRecentRecords().get(0));
        }
    }

    @Nested
    @DisplayName("SyncRecord")
    class SyncRecordTests {

        @Test
        void successFactoryPopulatesAllFieldsAndBuildsAMessage() {
            SyncRecord r = SyncRecord.success(
                    SyncRecord.ArtifactType.CHANNEL,
                    SyncRecord.Action.SAVE,
                    "ch-1",
                    "Channel One",
                    "abcdef");

            assertAll(
                    () -> assertTrue(r.isSuccess()),
                    () -> assertEquals("ch-1", r.getArtifactId()),
                    () -> assertEquals("Channel One", r.getArtifactName()),
                    () -> assertEquals(SyncRecord.ArtifactType.CHANNEL, r.getArtifactType()),
                    () -> assertEquals(SyncRecord.Action.SAVE, r.getAction()),
                    () -> assertEquals("abcdef", r.getCommitHash()),
                    () -> assertNotNull(r.getMessage()),
                    () -> assertTrue(r.getMessage().contains("Channel One")),
                    () -> assertTrue(r.getMessage().contains("SAVE")),
                    () -> assertTrue(r.getMessage().contains("CHANNEL")),
                    () -> assertNotNull(r.getTimestamp()),
                    () -> org.junit.jupiter.api.Assertions.assertNull(r.getError()));
        }

        @Test
        void failureFactoryPopulatesErrorAndStillBuildsAMessage() {
            SyncRecord r = SyncRecord.failure(
                    SyncRecord.ArtifactType.CODE_TEMPLATE_LIBRARY,
                    SyncRecord.Action.REMOVE,
                    "lib-1",
                    "Library",
                    "disk full");

            assertAll(
                    () -> assertFalse(r.isSuccess()),
                    () -> assertEquals("lib-1", r.getArtifactId()),
                    () -> assertEquals("Library", r.getArtifactName()),
                    () -> assertEquals(SyncRecord.ArtifactType.CODE_TEMPLATE_LIBRARY, r.getArtifactType()),
                    () -> assertEquals(SyncRecord.Action.REMOVE, r.getAction()),
                    () -> assertEquals("disk full", r.getError()),
                    () -> assertTrue(r.getMessage().contains("Failed")),
                    () -> assertTrue(r.getMessage().contains("disk full")));
        }

        @Test
        void artifactTypeEnumIncludesAllExpectedValues() {
            Set<String> names = new HashSet<>();
            for (SyncRecord.ArtifactType t : SyncRecord.ArtifactType.values()) {
                names.add(t.name());
            }
            assertTrue(names.contains("CHANNEL"));
            assertTrue(names.contains("CODE_TEMPLATE_LIBRARY"));
            assertTrue(names.contains("CHANNEL_GROUP"));
            assertTrue(names.contains("GLOBAL_SCRIPT"));
            assertTrue(names.contains("CONFIG_MAP"));
            assertFalse(names.contains("CODE_TEMPLATE"),
                    "CODE_TEMPLATE was removed in Phase 5 dead-code cleanup");
        }

        @Test
        void actionEnumIncludesAllExpectedValues() {
            Set<String> names = new HashSet<>();
            for (SyncRecord.Action a : SyncRecord.Action.values()) {
                names.add(a.name());
            }
            assertTrue(names.contains("SAVE"));
            assertTrue(names.contains("REMOVE"));
            assertTrue(names.contains("SYNC"));
            assertTrue(names.contains("PROMOTE"));
            assertTrue(names.contains("DEPLOY"));
            assertTrue(names.contains("UNDEPLOY"));
        }

        @Test
        void settersRoundTripForXStreamSerialisation() {
            SyncRecord r = new SyncRecord();
            r.setArtifactId("x");
            r.setArtifactName("Y");
            r.setArtifactType(SyncRecord.ArtifactType.GLOBAL_SCRIPT);
            r.setAction(SyncRecord.Action.SYNC);
            r.setCommitHash("0123456789abcdef");
            r.setMessage("msg");
            Instant ts = Instant.now();
            r.setTimestamp(ts);
            r.setSuccess(false);
            r.setError("oops");

            assertAll(
                    () -> assertEquals("x", r.getArtifactId()),
                    () -> assertEquals("Y", r.getArtifactName()),
                    () -> assertEquals(SyncRecord.ArtifactType.GLOBAL_SCRIPT, r.getArtifactType()),
                    () -> assertEquals(SyncRecord.Action.SYNC, r.getAction()),
                    () -> assertEquals("0123456789abcdef", r.getCommitHash()),
                    () -> assertEquals("msg", r.getMessage()),
                    () -> assertEquals(ts, r.getTimestamp()),
                    () -> assertFalse(r.isSuccess()),
                    () -> assertEquals("oops", r.getError()));
        }
    }

    @Nested
    @DisplayName("PromotionRequest / PromotionResult")
    class PromotionDtoTests {

        @Test
        void promotionRequestDefaultsAreSensible() {
            PromotionRequest req = new PromotionRequest();
            assertFalse(req.isDeploy(), "deploy defaults to false");
            assertTrue(req.isOverwrite(), "overwrite defaults to true");
            assertFalse(req.isDryRun(), "dryRun defaults to false");
            assertFalse(req.isFresh(), "fresh defaults to false");
            org.junit.jupiter.api.Assertions.assertNull(req.getCommitHash());
            org.junit.jupiter.api.Assertions.assertNull(req.getChannelIds());
        }

        @Test
        void promotionRequestSettersRoundTrip() {
            PromotionRequest req = new PromotionRequest();
            req.setDeploy(true);
            req.setOverwrite(false);
            req.setDryRun(true);
            req.setFresh(true);
            req.setCommitHash("0123456789abcdef");
            req.setChannelIds(Set.of("ch-1", "ch-2"));

            assertAll(
                    () -> assertTrue(req.isDeploy()),
                    () -> assertFalse(req.isOverwrite()),
                    () -> assertTrue(req.isDryRun()),
                    () -> assertTrue(req.isFresh()),
                    () -> assertEquals("0123456789abcdef", req.getCommitHash()),
                    () -> assertEquals(2, req.getChannelIds().size()));
        }

        @Test
        void promotionResultAccumulatesRecordsErrorsAndWarnings() {
            PromotionResult result = new PromotionResult();
            result.setSuccess(true);
            result.setCommitHash("deadbeef");
            result.setDryRun(false);
            result.setChannelsImported(3);
            result.setChannelsDeployed(2);

            result.addRecord(SyncRecord.success(
                    SyncRecord.ArtifactType.CHANNEL, SyncRecord.Action.PROMOTE,
                    "ch-1", "One", "deadbeef"));
            result.addRecord(SyncRecord.success(
                    SyncRecord.ArtifactType.CHANNEL, SyncRecord.Action.PROMOTE,
                    "ch-2", "Two", "deadbeef"));
            result.addError("couldn't import ch-3");
            result.addWarning("config map merge skipped");

            assertAll(
                    () -> assertTrue(result.isSuccess()),
                    () -> assertEquals("deadbeef", result.getCommitHash()),
                    () -> assertFalse(result.isDryRun()),
                    () -> assertEquals(3, result.getChannelsImported()),
                    () -> assertEquals(2, result.getChannelsDeployed()),
                    () -> assertEquals(2, result.getRecords().size()),
                    () -> assertEquals(List.of("couldn't import ch-3"), result.getErrors()),
                    () -> assertEquals(List.of("config map merge skipped"), result.getWarnings()));
        }

        @Test
        void promotionResultSettersAllowFullReplacement() {
            // Setters on the lists are here because XStream deserialises
            // into them during cross-JVM transfer; exercise them so they
            // don't rot.
            PromotionResult result = new PromotionResult();
            result.setRecords(List.of());
            result.setErrors(List.of("err"));
            result.setWarnings(List.of("warn"));
            assertEquals(0, result.getRecords().size());
            assertEquals("err", result.getErrors().get(0));
            assertEquals("warn", result.getWarnings().get(0));
        }
    }

    @Nested
    @DisplayName("PendingChange / PendingChangeList")
    class PendingChangeDtoTests {

        @Test
        void pendingChangeConstructorPopulatesFieldsAndTimestamps() {
            PendingChange c = new PendingChange(
                    PendingChange.Type.CHANNEL, "ch-1", "Channel", PendingChange.Action.MODIFY, 7);
            assertAll(
                    () -> assertEquals(PendingChange.Type.CHANNEL, c.getType()),
                    () -> assertEquals("ch-1", c.getId()),
                    () -> assertEquals("Channel", c.getName()),
                    () -> assertEquals(PendingChange.Action.MODIFY, c.getAction()),
                    () -> assertEquals(7, c.getRevision()),
                    () -> assertNotNull(c.getRecordedAt()),
                    () -> assertEquals("CHANNEL:ch-1", c.getKey()));
        }

        @Test
        void pendingChangeNoArgConstructorAllowsJacksonPopulation() {
            PendingChange c = new PendingChange();
            c.setType(PendingChange.Type.CODE_TEMPLATE_LIBRARY);
            c.setId("lib-1");
            c.setName("Lib");
            c.setAction(PendingChange.Action.DELETE);
            c.setRevision(2);
            Instant ts = Instant.now();
            c.setRecordedAt(ts);

            assertAll(
                    () -> assertEquals(PendingChange.Type.CODE_TEMPLATE_LIBRARY, c.getType()),
                    () -> assertEquals("lib-1", c.getId()),
                    () -> assertEquals("Lib", c.getName()),
                    () -> assertEquals(PendingChange.Action.DELETE, c.getAction()),
                    () -> assertEquals(2, c.getRevision()),
                    () -> assertEquals(ts, c.getRecordedAt()),
                    () -> assertEquals("CODE_TEMPLATE_LIBRARY:lib-1", c.getKey()));
        }

        @Test
        void pendingChangeListExposesSizeAndEmptyAsProxies() {
            PendingChangeList list = new PendingChangeList("alice");
            assertTrue(list.isEmpty());
            assertEquals(0, list.size());
            assertEquals("alice", list.getUsername());
            assertEquals(1, list.getVersion(),
                    "Default version number is 1 for the manifest schema");

            list.getChanges().add(new PendingChange(
                    PendingChange.Type.CHANNEL, "ch-1", "C", PendingChange.Action.MODIFY, 1));
            assertEquals(1, list.size());
            assertFalse(list.isEmpty());
        }

        @Test
        void pendingChangeListSettersRoundTripForJackson() {
            PendingChangeList list = new PendingChangeList();
            list.setUsername("bob");
            list.setVersion(42);
            list.setUpdated("2026-04-12T00:00:00Z");
            PendingChange one = new PendingChange(
                    PendingChange.Type.CHANNEL, "x", "X", PendingChange.Action.MODIFY, 1);
            list.setChanges(List.of(one));

            assertAll(
                    () -> assertEquals("bob", list.getUsername()),
                    () -> assertEquals(42, list.getVersion()),
                    () -> assertEquals("2026-04-12T00:00:00Z", list.getUpdated()),
                    () -> assertEquals(1, list.getChanges().size()),
                    () -> assertEquals(one, list.getChanges().get(0)));
        }
    }
}
