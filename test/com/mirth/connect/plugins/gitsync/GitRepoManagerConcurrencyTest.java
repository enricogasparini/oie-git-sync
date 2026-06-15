/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Concurrency tests for GitRepoManager. Validates that the fair gitLock
 * actually keeps concurrent setters, reads and commitToBranch calls from
 * stepping on each other. Runs against a real JGit repository on disk
 * (local-only; no remote) because JGit's own thread safety story is
 * frail enough that we want to see real operations interleaving, not
 * just mocked lock acquisition.
 *
 * These tests will be slow (seconds) compared to the unit tests —
 * they're deliberately I/O-bound.
 */
class GitRepoManagerConcurrencyTest {

    private static String seedLocalRepoAt(Path workingRepo) throws GitAPIException, IOException {
        try (Git git = Git.init().setDirectory(workingRepo.toFile())
                .setInitialBranch("main").call()) {
            Files.writeString(workingRepo.resolve("README.md"), "seed\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("seed")
                    .setAuthor("seed", "seed@example.com")
                    .setSign(false)
                    .call();
        }
        return workingRepo.toUri().toString();
    }

    @Test
    @DisplayName("Concurrent setter storm leaves the manager in a coherent state")
    void concurrentSettersAreSerialised(@TempDir Path workingRepo) throws Exception {
        GitRepoManager mgr = new GitRepoManager(workingRepo);
        mgr.setBranch("main");
        mgr.init(null);
        try {
            int threads = 16;
            int iterationsPerThread = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger exceptions = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterationsPerThread; i++) {
                            // Mix of setters — all lock-protected. Any
                            // race would corrupt the underlying String /
                            // int / boolean field under JMM visibility
                            // rules, but because gitLock holds on both
                            // write and read paths, we can't see a torn
                            // value.
                            mgr.setRemoteName("remote-" + threadIndex);
                            mgr.setBranch("branch-" + (i % 3));
                            mgr.setPushEnabled(i % 2 == 0);
                            mgr.setPushRetryCount(i % 7);
                            mgr.setPushRetryDelayMs(i);
                            mgr.setAuthorName("Author " + threadIndex);
                            mgr.setAuthorEmail("auth-" + threadIndex + "@t.example");
                            mgr.setCredentials("u" + i, "p" + i);
                        }
                    } catch (Throwable t2) {
                        exceptions.incrementAndGet();
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS),
                    "Setter-storm tasks should complete inside 20 seconds");
            assertEquals(0, exceptions.get(),
                    "No setter should have thrown — all mutations are lock-protected");

            // After the storm, the manager should still respond to reads.
            assertDoesNotThrow(() -> mgr.getCurrentBranch());
            assertDoesNotThrow(() -> mgr.getLatestCommitHash());
        } finally {
            mgr.close();
        }
    }

    @Test
    @DisplayName("Concurrent commitToBranch + setter calls do not corrupt the repo")
    void concurrentCommitsAndSettersInterleave(@TempDir Path workingRepo) throws Exception {
        seedLocalRepoAt(workingRepo);
        GitRepoManager mgr = new GitRepoManager(workingRepo);
        mgr.setBranch("main");
        mgr.setPushEnabled(false); // local-only; no push attempt
        mgr.init(null);
        try {
            int commitThreads = 4;
            int commitsPerThread = 5;
            int setterThreads = 4;
            int setterIterations = 2000;
            ExecutorService pool = Executors.newFixedThreadPool(commitThreads + setterThreads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger commitErrors = new AtomicInteger();
            AtomicInteger setterErrors = new AtomicInteger();
            AtomicInteger successfulCommits = new AtomicInteger();

            for (int t = 0; t < commitThreads; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < commitsPerThread; i++) {
                            final int iter = i;
                            final String file = "thread-" + threadIndex + "-iter-" + iter + ".txt";
                            final String content = threadIndex + ":" + iter;
                            String hash = mgr.commitToBranch(
                                    "gitsync/thread-" + threadIndex,
                                    root -> Files.writeString(
                                            root.resolve(file),
                                            content,
                                            StandardCharsets.UTF_8),
                                    List.of(file),
                                    null,
                                    "commit " + threadIndex + ":" + iter,
                                    "Thread " + threadIndex,
                                    "t" + threadIndex + "@test");
                            if (hash != null) {
                                successfulCommits.incrementAndGet();
                            }
                        }
                    } catch (Throwable ex) {
                        ex.printStackTrace();
                        commitErrors.incrementAndGet();
                    }
                });
            }

            for (int t = 0; t < setterThreads; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < setterIterations; i++) {
                            mgr.setAuthorName("SetAuthor" + threadIndex + "-" + i);
                            mgr.setRemoteName("setRemote-" + (i % 4));
                            if (i % 100 == 0) {
                                mgr.getLatestCommitHash();
                                mgr.getCurrentBranch();
                            }
                        }
                    } catch (Throwable ex) {
                        setterErrors.incrementAndGet();
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS),
                    "Mixed commit+setter workload should complete inside 60 seconds");

            assertEquals(0, commitErrors.get(),
                    "commitToBranch must not throw under concurrent setter load");
            assertEquals(0, setterErrors.get(),
                    "Setters must not throw while commits are in flight");
            assertEquals(commitThreads * commitsPerThread, successfulCommits.get(),
                    "Every commit should produce a hash");

            // After the storm, the repo is still on the base branch,
            // isInitialised, and can answer reads.
            assertEquals("main", mgr.getCurrentBranch(),
                    "Should have ended back on the base branch");
            assertTrue(mgr.isInitialised());
        } finally {
            mgr.close();
        }
    }

}
