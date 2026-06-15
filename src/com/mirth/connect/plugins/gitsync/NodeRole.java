/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

/**
 * How this OIE node participates in the Git sync flow.
 *
 * <ul>
 *   <li>{@link #CONTRIBUTOR} — users edit channels here and commit to Git.
 *   <li>{@link #RECEIVER} — this node only receives changes promoted from Git.
 *   <li>{@link #BOTH} — full functionality; typical for lower environments.
 * </ul>
 *
 * Production is usually a {@link #RECEIVER}, development a {@link #CONTRIBUTOR}, and lab/test
 * environments {@link #BOTH}.
 */
public enum NodeRole {
  CONTRIBUTOR,
  RECEIVER,
  BOTH;

  /**
   * Parses a case-insensitive role name. Unknown or blank input returns {@link #BOTH} (the
   * permissive default that keeps old configs working across upgrades).
   */
  public static NodeRole parse(String value) {
    if (value == null || value.isBlank()) {
      return BOTH;
    }
    try {
      return NodeRole.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return BOTH;
    }
  }

  public boolean isContributor() {
    return this == CONTRIBUTOR || this == BOTH;
  }

  public boolean isReceiver() {
    return this == RECEIVER || this == BOTH;
  }
}
