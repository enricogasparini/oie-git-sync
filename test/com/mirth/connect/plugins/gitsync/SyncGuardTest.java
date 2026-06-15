/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SyncGuardTest {

    @BeforeEach
    @AfterEach
    void resetState() {
        // ThreadLocals persist across tests sharing a thread — reset before
        // and after every test so one test's side effect can't corrupt the next.
        SyncGuard.clearSuppression();
    }

    @Nested
    @DisplayName("Default state and manual suppress/clear")
    class ManualLifecycle {

        @Test
        void defaultIsNotSuppressed() {
            assertFalse(SyncGuard.isSuppressed());
        }

        @Test
        void suppressSyncFlipsTheFlag() {
            SyncGuard.suppressSync();
            assertTrue(SyncGuard.isSuppressed());
        }

        @Test
        void clearSuppressionResetsToDefault() {
            SyncGuard.suppressSync();
            SyncGuard.clearSuppression();
            assertFalse(SyncGuard.isSuppressed());
        }

        @Test
        void clearSuppressionIsIdempotent() {
            // Should be safe to clear even when nothing has been suppressed.
            assertDoesNotThrow(SyncGuard::clearSuppression);
            assertFalse(SyncGuard.isSuppressed());
        }
    }

    @Nested
    @DisplayName("runSuppressed(Callable)")
    class RunSuppressedCallable {

        @Test
        void returnsTheCallablesValue() throws Exception {
            String result = SyncGuard.runSuppressed(() -> "answer");
            assertEquals("answer", result);
        }

        @Test
        void insideTheCallableIsSuppressed() throws Exception {
            AtomicBoolean sawSuppressed = new AtomicBoolean(false);
            SyncGuard.runSuppressed(() -> {
                sawSuppressed.set(SyncGuard.isSuppressed());
                return null;
            });
            assertTrue(sawSuppressed.get(), "Callable must see the flag set");
        }

        @Test
        void clearsSuppressionAfterNormalCompletion() throws Exception {
            SyncGuard.runSuppressed(() -> null);
            assertFalse(SyncGuard.isSuppressed());
        }

        @Test
        void clearsSuppressionAfterException() {
            RuntimeException boom = new RuntimeException("boom");
            Exception thrown = assertThrows(RuntimeException.class,
                    () -> SyncGuard.runSuppressed(() -> { throw boom; }));
            assertEquals(boom, thrown);
            assertFalse(SyncGuard.isSuppressed(),
                    "Even after the Callable threw, the flag must be cleared");
        }

        @Test
        void propagatesCheckedException() {
            Exception checked = new Exception("checked");
            Exception thrown = assertThrows(Exception.class,
                    () -> SyncGuard.runSuppressed(() -> { throw checked; }));
            assertEquals(checked, thrown);
        }
    }

    @Nested
    @DisplayName("runSuppressed(Runnable)")
    class RunSuppressedRunnable {

        @Test
        void insideTheRunnableIsSuppressed() {
            AtomicBoolean sawSuppressed = new AtomicBoolean(false);
            SyncGuard.runSuppressed(() -> sawSuppressed.set(SyncGuard.isSuppressed()));
            assertTrue(sawSuppressed.get());
        }

        @Test
        void clearsSuppressionAfterNormalCompletion() {
            SyncGuard.runSuppressed(() -> { /* no-op */ });
            assertFalse(SyncGuard.isSuppressed());
        }

        @Test
        void clearsSuppressionAfterRuntimeException() {
            assertThrows(RuntimeException.class,
                    () -> SyncGuard.runSuppressed(() -> { throw new RuntimeException("boom"); }));
            assertFalse(SyncGuard.isSuppressed());
        }
    }

    @Nested
    @DisplayName("Nested suppression")
    class Nested_ {

        /**
         * Contract: nested runSuppressed calls must restore the outer state,
         * not unconditionally clear. Prior to this test suite, the inner
         * finally block called clearSuppression() and dropped the outer's
         * flag on the way out, leaving the outer caller observing
         * isSuppressed() == false after its inner returned — a latent bug.
         * No production code currently nests these calls, but the contract
         * should hold.
         */
        @Test
        void innerRunSuppressedRestoresOuterState() throws Exception {
            SyncGuard.runSuppressed(() -> {
                assertTrue(SyncGuard.isSuppressed(), "outer sets the flag");
                SyncGuard.runSuppressed(() -> {
                    assertTrue(SyncGuard.isSuppressed(), "inner also sees it");
                    return null;
                });
                assertTrue(SyncGuard.isSuppressed(),
                        "After inner returns, outer must still see itself as suppressed");
                return null;
            });
            assertFalse(SyncGuard.isSuppressed(),
                    "After the outermost runSuppressed returns, cleaned up");
        }

        @Test
        void nestedExceptionStillRestoresOuterState() {
            AtomicBoolean outerStillSuppressedAfterInnerThrew = new AtomicBoolean(false);
            RuntimeException inner = new RuntimeException("inner boom");
            RuntimeException caught = assertThrows(RuntimeException.class,
                    () -> SyncGuard.runSuppressed((Runnable) () -> {
                        try {
                            SyncGuard.runSuppressed((Runnable) () -> { throw inner; });
                        } catch (RuntimeException e) {
                            outerStillSuppressedAfterInnerThrew.set(SyncGuard.isSuppressed());
                            throw e;
                        }
                    }));
            assertEquals(inner, caught);
            assertTrue(outerStillSuppressedAfterInnerThrew.get(),
                    "When the inner throws, the outer's suppression state must still be in place");
            assertFalse(SyncGuard.isSuppressed(),
                    "Once the outer has also unwound, the flag is cleared");
        }
    }

    @Nested
    @DisplayName("Thread isolation")
    class ThreadIsolation {

        @Test
        void oneThreadsSuppressionDoesNotLeakIntoAnother() throws Exception {
            SyncGuard.suppressSync();
            assertTrue(SyncGuard.isSuppressed(), "sanity: this thread is suppressed");

            AtomicReference<Boolean> otherThreadSeen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread other = new Thread(() -> {
                otherThreadSeen.set(SyncGuard.isSuppressed());
                done.countDown();
            });
            other.start();
            assertTrue(done.await(2, TimeUnit.SECONDS));
            other.join();

            assertFalse(otherThreadSeen.get(),
                    "The other thread's ThreadLocal must not see this thread's suppression");
            assertTrue(SyncGuard.isSuppressed(),
                    "This thread's own suppression state must still be set");
        }
    }
}
