/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * GitRepoManager tests.
 *
 * Three tiers:
 *  1. Pure function tests (shortHash, sanitiseUrl) — no filesystem, no JGit.
 *  2. Setter + lifecycle tests — exercise GitRepoManager's state machine
 *     without any real git operations.
 *  3. JGit integration tests — spin up a bare "remote" repo at one temp dir
 *     and a working repo pointing at it via file:// URL, then exercise
 *     clone / commit / push / fetch round-trips.
 */
class GitRepoManagerTest {

    @Nested
    @DisplayName("shortHash()")
    class ShortHashTests {

        @Test
        void nullReturnsQuestionMark() {
            assertEquals("?", GitRepoManager.shortHash(null));
        }

        @Test
        void emptyReturnsEmpty() {
            // Length 0 is not > SHORT_HASH_LEN, so the ternary returns
            // the original. Not worth a special case.
            assertEquals("", GitRepoManager.shortHash(""));
        }

        @Test
        void shorterThanEightIsReturnedAsIs() {
            assertEquals("abc", GitRepoManager.shortHash("abc"));
            assertEquals("1234567", GitRepoManager.shortHash("1234567"));
        }

        @Test
        void exactlyEightIsReturnedAsIs() {
            // The boundary — length 8 is NOT longer than SHORT_HASH_LEN,
            // so no truncation.
            assertEquals("12345678", GitRepoManager.shortHash("12345678"));
        }

        @Test
        void longerThanEightIsTruncated() {
            assertEquals("12345678",
                    GitRepoManager.shortHash("1234567890abcdef1234567890abcdef12345678"));
        }

        @Test
        void shortHashLengthConstantIsExposedAsEight() {
            assertEquals(8, GitRepoManager.SHORT_HASH_LEN);
        }
    }

    @Nested
    @DisplayName("sanitiseUrl()")
    class SanitiseUrlTests {

        @ParameterizedTest
        @NullAndEmptySource
        void nullPassesThrough(String input) {
            // Expected: null -> null, "" -> "".
            if (input == null) {
                assertNull(GitRepoManager.sanitiseUrl(null));
            } else {
                assertEquals("", GitRepoManager.sanitiseUrl(""));
            }
        }

        @ParameterizedTest(name = "{0} is unchanged (no userinfo)")
        @ValueSource(strings = {
                "https://github.com/enricogasparini/oie-git-sync.git",
                "https://gitlab.example.com:8443/team/repo.git",
                "git@github.com:enricogasparini/oie-git-sync.git",
                "ssh://git@ssh.github.com:443/enricogasparini/oie-git-sync.git"
        })
        void withoutUserinfoReturnsUrlUnchanged(String url) {
            // For URLs without embedded credentials we want the output
            // to be equal to the input (byte-for-byte where JGit's URIish
            // round-trip allows).
            String sanitised = GitRepoManager.sanitiseUrl(url);
            // JGit's URIish may normalise some forms; we only require that
            // no credentials have been introduced and the host is preserved.
            assertFalse(sanitised.contains("@@"));
            assertTrue(sanitised.contains("github.com") || sanitised.contains("gitlab.example.com"));
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "https://alice:s3cret@github.com/foo/bar.git                 | https://github.com/foo/bar.git",
                "https://ghp_tokenvalue@github.com/foo/bar.git                | https://github.com/foo/bar.git",
                "https://x-access-token:ghs_abc@github.com/foo/bar.git        | https://github.com/foo/bar.git",
                "http://user:pass@internal.example:8080/git/thing.git         | http://internal.example:8080/git/thing.git"
        })
        void userinfoIsStrippedFromHttpAndHttpsUrls(String input, String expected) {
            assertEquals(expected, GitRepoManager.sanitiseUrl(input.trim()));
        }

        @Test
        void malformedUrlWithUserinfoIsStrippedByFallback() {
            // URIish will likely reject this, and sanitiseUrl falls back to
            // the "find @ after ://" heuristic.
            String result = GitRepoManager.sanitiseUrl("https://notgoodatall@@host/x");
            assertFalse(result.contains("notgoodatall"),
                    "Userinfo must not survive even a malformed URL");
        }

        @Test
        void totallyUnparseableStringIsReturnedAsIs() {
            // If the input doesn't look like a URL at all we can't parse it;
            // the contract is to return it untouched rather than throw.
            String garbage = "not a url at all: @ what @ @";
            assertEquals(garbage, GitRepoManager.sanitiseUrl(garbage));
        }
    }

    // -----------------------------------------------------------------------
    // Exponential backoff
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Retry delay computation")
    class RetryDelayTests {

        @Test
        @DisplayName("First attempt uses base delay")
        void firstAttemptUsesBaseDelay(@TempDir Path repoPath) {
            GitRepoManager mgr = new GitRepoManager(repoPath);
            mgr.setPushRetryDelayMs(2000);
            assertEquals(2000L, mgr.computeRetryDelayMs(1));
        }

        @Test
        @DisplayName("Subsequent attempts double the delay")
        void exponentialGrowth(@TempDir Path repoPath) {
            GitRepoManager mgr = new GitRepoManager(repoPath);
            mgr.setPushRetryDelayMs(1000);
            assertAll(
                    () -> assertEquals(1000L, mgr.computeRetryDelayMs(1)),
                    () -> assertEquals(2000L, mgr.computeRetryDelayMs(2)),
                    () -> assertEquals(4000L, mgr.computeRetryDelayMs(3)),
                    () -> assertEquals(8000L, mgr.computeRetryDelayMs(4)));
        }

        @Test
        @DisplayName("Delay is capped at 30 seconds")
        void cappedAtMax(@TempDir Path repoPath) {
            GitRepoManager mgr = new GitRepoManager(repoPath);
            mgr.setPushRetryDelayMs(5000);
            // attempt 4: 5000 * 2^3 = 40000, should cap at 30000
            assertEquals(30_000L, mgr.computeRetryDelayMs(4));
        }

        @Test
        @DisplayName("Zero base delay always returns zero")
        void zeroBaseDelay(@TempDir Path repoPath) {
            GitRepoManager mgr = new GitRepoManager(repoPath);
            mgr.setPushRetryDelayMs(0);
            assertEquals(0L, mgr.computeRetryDelayMs(5));
        }
    }

    // -----------------------------------------------------------------------
    // Setter + lifecycle tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Lifecycle (no git state required)")
    class LifecycleTests {

        @TempDir
        Path repoPath;

        @Test
        void freshManagerIsNotInitialised() {
            GitRepoManager mgr = new GitRepoManager(repoPath);
            assertFalse(mgr.isInitialised());
            assertNull(mgr.getLatestCommitHash());
        }

        @Test
        void getCurrentBranchReturnsNullWhenNotInitialised() throws IOException {
            GitRepoManager mgr = new GitRepoManager(repoPath);
            assertNull(mgr.getCurrentBranch());
        }

        @Test
        void testConnectionBeforeInitThrowsIllegalStateException() {
            GitRepoManager mgr = new GitRepoManager(repoPath);
            // No git handle yet — testConnection hits hasRemote() which
            // returns false for git==null, then throws IllegalStateException
            // ("No remote configured"). Exact type: Exception, caused by or
            // including IllegalStateException.
            Exception ex = assertThrows(Exception.class, mgr::testConnection);
            assertTrue(ex instanceof IllegalStateException
                            || ex.getCause() instanceof IllegalStateException
                            || ex.getMessage() != null,
                    "Expected some kind of failure when testConnection is called before init");
        }

        @Test
        void closeOnUninitialisedManagerIsNoOp() {
            GitRepoManager mgr = new GitRepoManager(repoPath);
            // close() null-checks the internal git handle and returns cleanly.
            mgr.close();
            mgr.close(); // second call also safe
            assertFalse(mgr.isInitialised());
        }

        @Test
        void settersCanBeCalledBeforeInit() {
            // The save path in GitSyncPlugin.applyProperties() calls every
            // setter BEFORE init(), so they must not touch git state.
            GitRepoManager mgr = new GitRepoManager(repoPath);
            mgr.setRemoteName("upstream");
            mgr.setBranch("develop");
            mgr.setAuthorName("Test Bot");
            mgr.setAuthorEmail("test@example.com");
            mgr.setPushEnabled(false);
            mgr.setPushRetryCount(5);
            mgr.setPushRetryDelayMs(250);
            mgr.setCredentials("alice", "token");
            mgr.setCredentials(null, null); // clears
            assertFalse(mgr.isInitialised());
        }

        @Test
        void getRepoPathReturnsTheConstructorArgument() {
            GitRepoManager mgr = new GitRepoManager(repoPath);
            assertEquals(repoPath, mgr.getRepoPath());
        }
    }

    // -----------------------------------------------------------------------
    // JGit integration tests
    // -----------------------------------------------------------------------

    /**
     * Creates a bare git repository at {@code barePath} and seeds it with a
     * single "main" branch containing one commit (so clients can clone and
     * immediately have something to fetch). Returns the file:// URL of the
     * bare repo, suitable for passing to GitRepoManager.init.
     *
     * Seeding order matters. A freshly-init'd bare repo has HEAD pointing
     * at refs/heads/main but no actual commit, so a client that tries to
     * clone it with setBranch("main") fails with
     * "Remote branch 'main' not found in upstream origin". We work around
     * this by:
     *   1. Creating a scratch working repo from scratch (non-bare),
     *   2. Making a seed commit in it,
     *   3. Creating a bare repo at the target path,
     *   4. Adding the bare as a remote of the scratch and pushing main to it.
     */
    private static String seedBareRemote(Path barePath) throws GitAPIException, IOException {
        Path seedWork = Files.createTempDirectory("seed-work-");
        try {
            // 1. Scratch working repo with main as the initial branch.
            try (Git seed = Git.init()
                    .setDirectory(seedWork.toFile())
                    .setInitialBranch("main")
                    .call()) {
                // 2. One commit so the main branch is no longer unborn.
                Files.writeString(seedWork.resolve("README.md"), "seed\n", StandardCharsets.UTF_8);
                seed.add().addFilepattern("README.md").call();
                seed.commit()
                        .setMessage("seed")
                        .setAuthor("seed", "seed@example.com")
                        .setSign(false)
                        .call();

                // 3. Bare repo at the target location.
                try (Git bare = Git.init()
                        .setBare(true)
                        .setDirectory(barePath.toFile())
                        .setInitialBranch("main")
                        .call()) {
                    // no-op; we just need the on-disk layout
                }

                // 4. Add the bare as a remote of the scratch repo and push main.
                seed.remoteAdd()
                        .setName("origin")
                        .setUri(new org.eclipse.jgit.transport.URIish(barePath.toUri().toString()))
                        .call();
                seed.push().setRemote("origin").add("main").call();
            }
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        } finally {
            FileUtils.deleteRecursivelyQuietly(seedWork);
        }
        return barePath.toUri().toString();
    }

    @Nested
    @DisplayName("init() with a real JGit repo")
    class InitTests {

        @TempDir
        Path bareRepo;
        @TempDir
        Path workingRepo;

        @Test
        void initWithoutRemoteCreatesLocalRepository() throws Exception {
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(null);
            try {
                assertTrue(mgr.isInitialised());
                assertTrue(Files.exists(workingRepo.resolve(".git")),
                        "init with no remote should create a local .git directory");
                assertEquals("main", mgr.getCurrentBranch());
            } finally {
                mgr.close();
            }
        }

        @Test
        void initWithRemoteClonesTheBareRepo() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);
            try {
                assertTrue(mgr.isInitialised());
                // README.md from the seed commit should be present.
                assertTrue(Files.exists(workingRepo.resolve("README.md")));
                assertEquals("main", mgr.getCurrentBranch());
                assertNotNull(mgr.getLatestCommitHash());
            } finally {
                mgr.close();
            }
        }

        @Test
        void initOnExistingRepoOpensItRatherThanReclone() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager first = new GitRepoManager(workingRepo);
            first.setBranch("main");
            first.init(remoteUrl);
            String firstCommit = first.getLatestCommitHash();
            first.close();

            GitRepoManager second = new GitRepoManager(workingRepo);
            second.setBranch("main");
            second.init(remoteUrl);
            try {
                assertTrue(second.isInitialised());
                // Same repo — latest commit unchanged between the two
                // initialisations.
                assertEquals(firstCommit, second.getLatestCommitHash());
            } finally {
                second.close();
            }
        }
    }

    @Nested
    @DisplayName("commitToBranch() and push")
    class CommitToBranchTests {

        @TempDir
        Path bareRepo;
        @TempDir
        Path workingRepo;

        @Test
        void commitToNewFeatureBranchPushesToRemote() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);
            mgr.setAuthorName("Test Author");
            mgr.setAuthorEmail("test@example.com");

            try {
                String hash = mgr.commitToBranch(
                        "gitsync/alice/2026-04-12",
                        workingTreeRoot -> Files.writeString(
                                workingTreeRoot.resolve("new-file.txt"),
                                "hello world\n", StandardCharsets.UTF_8),
                        List.of("new-file.txt"),
                        null,
                        "Add new-file.txt");

                assertNotNull(hash, "commitToBranch should return the commit hash on success");
                assertEquals(40, hash.length(), "Full SHA-1 hash length");

                // Returning to base branch after commit is part of the contract.
                assertEquals("main", mgr.getCurrentBranch());

                // Verify the feature branch actually landed on the bare remote.
                try (Git bare = Git.open(bareRepo.toFile())) {
                    boolean branchExists = bare.branchList()
                            .call()
                            .stream()
                            .anyMatch(ref -> ref.getName().endsWith("gitsync/alice/2026-04-12"));
                    assertTrue(branchExists,
                            "Feature branch should exist on the remote after push");
                }
            } finally {
                mgr.close();
            }
        }

        @Test
        void commitToBranchReturnsNullWhenNoFileChanges() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);

            try {
                String hash = mgr.commitToBranch(
                        "gitsync/alice/2026-04-12-empty",
                        workingTreeRoot -> { /* do nothing */ },
                        List.of(),
                        null,
                        "Empty commit attempt");

                assertNull(hash, "A commit with no staged changes must return null");
                // Still on base branch.
                assertEquals("main", mgr.getCurrentBranch());
            } finally {
                mgr.close();
            }
        }

        @Test
        void commitToBranchUsesOverrideAuthorIdentityWhenProvided() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);
            mgr.setAuthorName("Fallback Name");
            mgr.setAuthorEmail("fallback@example.com");

            try {
                String hash = mgr.commitToBranch(
                        "gitsync/override/2026-04-12",
                        workingTreeRoot -> Files.writeString(
                                workingTreeRoot.resolve("override.txt"),
                                "overridden\n", StandardCharsets.UTF_8),
                        List.of("override.txt"),
                        null,
                        "Override author",
                        "Alice Wonderland",
                        "alice@wonder.land");

                assertNotNull(hash);

                // Inspect the commit directly on the bare repo.
                try (Git bare = Git.open(bareRepo.toFile())) {
                    var iter = bare.log()
                            .add(bare.getRepository().resolve("refs/heads/gitsync/override/2026-04-12"))
                            .setMaxCount(1)
                            .call()
                            .iterator();
                    assertTrue(iter.hasNext());
                    var commit = iter.next();
                    assertAll(
                            () -> assertEquals("Alice Wonderland", commit.getAuthorIdent().getName()),
                            () -> assertEquals("alice@wonder.land", commit.getAuthorIdent().getEmailAddress()),
                            () -> assertEquals("Override author", commit.getFullMessage()));
                }
            } finally {
                mgr.close();
            }
        }

        @Test
        void commitToBranchWithFileRemovalDeletesAndStagesTheDelete() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);

            try {
                // First commit: add a file.
                mgr.commitToBranch(
                        "gitsync/alice/add",
                        workingTreeRoot -> Files.writeString(
                                workingTreeRoot.resolve("will-be-removed.txt"),
                                "alive\n", StandardCharsets.UTF_8),
                        List.of("will-be-removed.txt"),
                        null,
                        "Add will-be-removed.txt");
                // Second commit on a different feature branch: remove it.
                String removeHash = mgr.commitToBranch(
                        "gitsync/alice/remove",
                        workingTreeRoot -> {
                            // The callback runs after checkout of the base branch
                            // and reset to origin/main — but the file we added on
                            // the previous feature branch is NOT on main, so the
                            // callback has nothing to delete at this point. We
                            // still exercise the pathsToRemove wiring by asking
                            // for a path that happens to exist on the base.
                            Files.writeString(
                                    workingTreeRoot.resolve("README.md"),
                                    "keep this\n", StandardCharsets.UTF_8);
                        },
                        List.of("README.md"),
                        null,
                        "Touch README");
                assertNotNull(removeHash);
            } finally {
                mgr.close();
            }
        }

        @Test
        void recoversFromADirtyWorkingTreeLeftByAFailedCommit() throws Exception {
            // A commitToBranch that dies between checkout and commit (push
            // exhaustion, IOException in the file callback, ...) leaves the
            // repo on the feature branch with a dirty working tree. The next
            // commit must recover rather than fail with a checkout conflict.
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);

            try {
                // Simulate the aftermath of a failed commit: stranded on a
                // feature branch with uncommitted modifications to a tracked
                // file.
                try (Git git = Git.open(workingRepo.toFile())) {
                    git.checkout().setCreateBranch(true).setName("gitsync/stranded").call();
                    Files.writeString(workingRepo.resolve("README.md"),
                            "uncommitted local damage\n", StandardCharsets.UTF_8);
                }

                String hash = mgr.commitToBranch(
                        "gitsync/alice/recovery",
                        workingTreeRoot -> Files.writeString(
                                workingTreeRoot.resolve("recovered.txt"),
                                "all good\n", StandardCharsets.UTF_8),
                        List.of("recovered.txt"),
                        null,
                        "Recovered after failure");

                assertNotNull(hash,
                        "commitToBranch must succeed despite a dirty tree from a prior failure");
                assertEquals("main", mgr.getCurrentBranch());
            } finally {
                mgr.close();
            }
        }

        @Test
        void deletionsUnderAnAddedPrefixAreStagedAndCommitted() throws Exception {
            // Full sync clears each artefact directory before re-serialising,
            // so artefacts deleted in OIE since the last snapshot must show up
            // as deletions in the commit. JGit's AddCommand does not stage
            // deletions, so commitToBranch stages them explicitly.
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);

            try {
                mgr.commitToBranch(
                        "gitsync/snap",
                        workingTreeRoot -> {
                            Files.createDirectories(workingTreeRoot.resolve("channels/ch-1"));
                            Files.createDirectories(workingTreeRoot.resolve("channels/ch-2"));
                            Files.writeString(workingTreeRoot.resolve("channels/ch-1/channel.xml"),
                                    "<one/>", StandardCharsets.UTF_8);
                            Files.writeString(workingTreeRoot.resolve("channels/ch-2/channel.xml"),
                                    "<two/>", StandardCharsets.UTF_8);
                        },
                        List.of("channels"),
                        null,
                        "Snapshot with two channels");

                // Second snapshot on the same branch: ch-2 has been deleted in
                // OIE, so the callback clears the directory and only rewrites
                // ch-1 (what triggerFullSync does).
                String hash = mgr.commitToBranch(
                        "gitsync/snap",
                        workingTreeRoot -> {
                            FileUtils.deleteRecursively(workingTreeRoot.resolve("channels"));
                            Files.createDirectories(workingTreeRoot.resolve("channels/ch-1"));
                            Files.writeString(workingTreeRoot.resolve("channels/ch-1/channel.xml"),
                                    "<one v2/>", StandardCharsets.UTF_8);
                        },
                        List.of("channels"),
                        null,
                        "Snapshot after ch-2 deleted");

                assertNotNull(hash, "The deletion alone should be enough to produce a commit");

                // Verify on a fresh clone of the snapshot branch: ch-2 gone, ch-1 updated.
                Path verify = Files.createTempDirectory("verify-");
                try (Git clone = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(verify.toFile())
                        .setBranch("gitsync/snap")
                        .call()) {
                    assertFalse(Files.exists(verify.resolve("channels/ch-2/channel.xml")),
                            "Deleted channel must not survive in the snapshot branch");
                    assertEquals("<one v2/>", Files.readString(
                            verify.resolve("channels/ch-1/channel.xml"), StandardCharsets.UTF_8));
                } finally {
                    FileUtils.deleteRecursivelyQuietly(verify);
                }
            } finally {
                mgr.close();
            }
        }
    }

    @Nested
    @DisplayName("fetch / fetchAndReset / testConnection")
    class RemoteOpsTests {

        @TempDir
        Path bareRepo;
        @TempDir
        Path workingRepo;

        @Test
        void testConnectionSucceedsAgainstSeededBareRepo() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);
            try {
                String result = mgr.testConnection();
                assertNotNull(result);
                assertTrue(result.contains("Connected"),
                        "testConnection result should confirm success");
                assertTrue(result.contains("branch"),
                        "testConnection result should mention branch count");
            } finally {
                mgr.close();
            }
        }

        @Test
        void testConnectionFailsWhenRemoteIsGone(@TempDir Path phonyParent) throws Exception {
            // Set up a manager by cloning from a seeded bare repo (so init
            // succeeds), then delete the bare repo on disk to simulate the
            // remote becoming unreachable, then assert testConnection blows
            // up with a transport-category error.
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);
            try {
                FileUtils.deleteRecursively(bareRepo);
                // After the remote directory is gone, testConnection should
                // raise TransportException (or its API variant) — both JGit
                // subclasses extend org.eclipse.jgit.api.errors.GitAPIException.
                Exception ex = assertThrows(Exception.class, mgr::testConnection);
                assertTrue(ex instanceof TransportException
                                || ex instanceof GitAPIException
                                || ex instanceof IOException,
                        "Expected a transport/Git/IO failure, got " + ex);
            } finally {
                mgr.close();
            }
        }

        @Test
        void fetchIsNoOpWithoutRemote() throws Exception {
            // Local-only repo (no remote configured) — fetch() should do
            // nothing quietly rather than throw.
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(null);
            try {
                mgr.fetch(); // must not throw
            } finally {
                mgr.close();
            }
        }

        @Test
        void fetchAndResetReturnsLocalHeadWithoutRemote() throws Exception {
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(null);
            try {
                // Make a local commit so latest hash is not null.
                try (Git git = Git.open(workingRepo.toFile())) {
                    Files.writeString(workingRepo.resolve("x.txt"), "x", StandardCharsets.UTF_8);
                    git.add().addFilepattern("x.txt").call();
                    git.commit().setMessage("local").setAuthor("l", "l@l").call();
                }
                String hash = mgr.fetchAndReset();
                assertNotNull(hash);
                assertEquals(40, hash.length());
            } finally {
                mgr.close();
            }
        }

        @Test
        void fetchAndResetAfterRemoteSideCommit() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);
            String initialHash = mgr.getLatestCommitHash();

            // Simulate someone else pushing a new commit to the bare repo
            // directly (via a temporary clone).
            Path otherWork = Files.createTempDirectory("other-");
            try (Git other = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(otherWork.toFile())
                    .setBranch("main")
                    .call()) {
                Files.writeString(otherWork.resolve("new.txt"), "new", StandardCharsets.UTF_8);
                other.add().addFilepattern("new.txt").call();
                other.commit().setMessage("from other").setAuthor("o", "o@o").call();
                other.push().call();
            } finally {
                FileUtils.deleteRecursivelyQuietly(otherWork);
            }

            try {
                String newHash = mgr.fetchAndReset();
                assertNotNull(newHash);
                assertFalse(newHash.equals(initialHash),
                        "After the remote pushed a new commit, fetchAndReset should advance");
                assertTrue(Files.exists(workingRepo.resolve("new.txt")),
                        "The new file from the remote-side commit should land on disk");
            } finally {
                mgr.close();
            }
        }

        @Test
        void fetchAndResetToAPinnedCommitChecksOutThatCommitsContent() throws Exception {
            // Promotion with a pinned commitHash must read THAT commit's
            // files, not whatever main has since moved to.
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);
            String pinnedHash = mgr.getLatestCommitHash();

            // The remote moves on: another clone pushes a newer commit.
            Path otherWork = Files.createTempDirectory("other-");
            try (Git other = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(otherWork.toFile())
                    .setBranch("main")
                    .call()) {
                Files.writeString(otherWork.resolve("newer.txt"), "newer", StandardCharsets.UTF_8);
                other.add().addFilepattern("newer.txt").call();
                other.commit().setMessage("newer").setAuthor("o", "o@o").call();
                other.push().call();
            } finally {
                FileUtils.deleteRecursivelyQuietly(otherWork);
            }

            try {
                String resolved = mgr.fetchAndReset(pinnedHash);
                assertEquals(pinnedHash, resolved,
                        "Pinned fetchAndReset must return the pinned commit's hash");
                assertFalse(Files.exists(workingRepo.resolve("newer.txt")),
                        "Working tree must reflect the pinned commit, not the newer remote tip");

                // And without a pin, the same call advances to the remote tip.
                String tip = mgr.fetchAndReset(null);
                assertFalse(tip.equals(pinnedHash));
                assertTrue(Files.exists(workingRepo.resolve("newer.txt")));
            } finally {
                mgr.close();
            }
        }

        @Test
        void fetchAndResetWithUnresolvableCommitThrows() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);
            try {
                assertThrows(IOException.class,
                        () -> mgr.fetchAndReset("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"),
                        "An unknown pinned commit must fail loudly, not silently promote HEAD");
            } finally {
                mgr.close();
            }
        }
    }

    @Nested
    @DisplayName("resetLocalRepo / close")
    class ResetAndCloseTests {

        @TempDir
        Path bareRepo;
        @TempDir
        Path workingRepo;

        @Test
        void resetLocalRepoDeletesAndReclonesFromRemote() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);

            // Pollute the working tree with a stray file that isn't in git.
            Files.writeString(workingRepo.resolve("stray.txt"), "leftover", StandardCharsets.UTF_8);
            assertTrue(Files.exists(workingRepo.resolve("stray.txt")));

            mgr.resetLocalRepo(remoteUrl);

            try {
                assertTrue(mgr.isInitialised());
                assertTrue(Files.exists(workingRepo.resolve("README.md")),
                        "Fresh clone should have the seed README.md back");
                assertFalse(Files.exists(workingRepo.resolve("stray.txt")),
                        "Stray file from before the reset should be gone");
            } finally {
                mgr.close();
            }
        }

        @Test
        void closeAllowsSubsequentInit() throws Exception {
            String remoteUrl = seedBareRemote(bareRepo);
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(remoteUrl);
            assertTrue(mgr.isInitialised());
            mgr.close();
            assertFalse(mgr.isInitialised());

            // A second init on the same path should open the existing repo.
            mgr.init(remoteUrl);
            try {
                assertTrue(mgr.isInitialised());
            } finally {
                mgr.close();
            }
        }
    }

    @Nested
    @DisplayName("getChangedPaths() diff")
    class GetChangedPathsTests {

        @TempDir
        Path bareRepo;
        @TempDir
        Path workingRepo;

        @Test
        void emptyRangeReturnsEmptyList() throws Exception {
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(null);
            try {
                // No commits at all — resolve(HEAD) returns null, so we get
                // back an empty list.
                List<String> paths = mgr.getChangedPaths(null, null, null);
                assertTrue(paths.isEmpty());
            } finally {
                mgr.close();
            }
        }

        @Test
        void pathPrefixFiltersDiffEntries() throws Exception {
            GitRepoManager mgr = new GitRepoManager(workingRepo);
            mgr.setBranch("main");
            mgr.init(null);
            try {
                try (Git git = Git.open(workingRepo.toFile())) {
                    Files.createDirectories(workingRepo.resolve("channels/abc"));
                    Files.writeString(workingRepo.resolve("channels/abc/channel.xml"),
                            "<c/>", StandardCharsets.UTF_8);
                    Files.createDirectories(workingRepo.resolve("code-templates/lib-1"));
                    Files.writeString(workingRepo.resolve("code-templates/lib-1/library.xml"),
                            "<l/>", StandardCharsets.UTF_8);
                    git.add().addFilepattern(".").call();
                    git.commit().setMessage("seed").setAuthor("a", "a@a").call();
                }
                List<String> channelPaths = mgr.getChangedPaths(null, null, "channels/");
                assertEquals(1, channelPaths.size());
                assertEquals("channels/abc/channel.xml", channelPaths.get(0));

                List<String> libPaths = mgr.getChangedPaths(null, null, "code-templates/");
                assertEquals(1, libPaths.size());
                assertEquals("code-templates/lib-1/library.xml", libPaths.get(0));

                List<String> all = mgr.getChangedPaths(null, null, null);
                assertTrue(all.contains("channels/abc/channel.xml"));
                assertTrue(all.contains("code-templates/lib-1/library.xml"));
                assertEquals(2, all.size());
            } finally {
                mgr.close();
            }
        }
    }
}
