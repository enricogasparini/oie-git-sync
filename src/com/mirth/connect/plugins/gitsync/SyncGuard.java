/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import java.util.concurrent.Callable;

/**
 * Prevents circular synchronisation when the promotion endpoint imports channels.
 *
 * <p>When Direction B (Git-to-OIE) updates a channel via ChannelController, the controller invokes
 * ChannelPlugin.save() for all registered plugins, including GitSyncPlugin. Without this guard, the
 * imported channel would be committed back to Git, creating an infinite loop.
 *
 * <p>Uses a ThreadLocal flag because OIE processes REST requests in dedicated Jetty threads and
 * controller calls are synchronous within the same thread.
 */
public final class SyncGuard {

  private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> false);

  private SyncGuard() {}

  public static void suppressSync() {
    SUPPRESSED.set(true);
  }

  public static void clearSuppression() {
    SUPPRESSED.remove();
  }

  public static boolean isSuppressed() {
    return SUPPRESSED.get();
  }

  /**
   * Executes the given action with sync suppressed. On exit (normal or exceptional) the suppression
   * state that was in effect before the call is restored — nested calls therefore leave the outer
   * suppression intact, and the outermost call unsets the flag entirely.
   */
  public static <T> T runSuppressed(Callable<T> action) throws Exception {
    boolean previouslySuppressed = SUPPRESSED.get();
    suppressSync();
    try {
      return action.call();
    } finally {
      restore(previouslySuppressed);
    }
  }

  /** Void variant of {@link #runSuppressed(Callable)}. */
  public static void runSuppressed(Runnable action) {
    boolean previouslySuppressed = SUPPRESSED.get();
    suppressSync();
    try {
      action.run();
    } finally {
      restore(previouslySuppressed);
    }
  }

  /**
   * Restores the ThreadLocal to the state captured before the enclosing {@link #runSuppressed}
   * began. When there was no outer suppression we call {@link ThreadLocal#remove()} rather than
   * {@code set(false)} so the slot is not left dangling on the thread between requests.
   */
  private static void restore(boolean previouslySuppressed) {
    if (previouslySuppressed) {
      SUPPRESSED.set(true);
    } else {
      SUPPRESSED.remove();
    }
  }
}
