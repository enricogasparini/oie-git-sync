/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

/**
 * Authentication scheme for the remote Git repository.
 *
 * <ul>
 *   <li>{@link #NONE} — no authentication (public repos or SSH agent).
 *   <li>{@link #HTTPS_TOKEN} — HTTPS with a Personal Access Token. Username is ignored by most
 *       providers when using tokens but some still require it.
 *   <li>{@link #HTTPS_BASIC} — HTTPS with username and password. Use only for legacy servers that
 *       do not support tokens.
 *   <li>{@link #SSH_KEY} — SSH with a key from the local SSH agent or the system default identity
 *       (no in-plugin key management).
 * </ul>
 */
public enum CredentialType {
  NONE,
  HTTPS_TOKEN,
  HTTPS_BASIC,
  SSH_KEY;

  public static CredentialType parse(String value) {
    if (value == null || value.isBlank()) {
      return NONE;
    }
    try {
      return CredentialType.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return NONE;
    }
  }

  public boolean requiresUsernamePassword() {
    return this == HTTPS_TOKEN || this == HTTPS_BASIC;
  }
}
