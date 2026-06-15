/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import com.mirth.commons.encryption.Encryptor;
import com.mirth.connect.server.controllers.ConfigurationController;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Encrypts credential material at rest using OIE's configured {@link Encryptor}.
 *
 * <p>Values are tagged with the {@link #ENCRYPTED_PREFIX} so we can tell whether a stored value has
 * already been encrypted or is a fresh plaintext from the settings panel. This lets the save path
 * be idempotent: re-saving the form without editing the password does not re-encrypt or re-persist
 * anything.
 *
 * <p>OIE initialises its {@code Encryptor} during server startup; until then {@code
 * ConfigurationController.getEncryptor()} returns {@code null}. Calls made before that point fall
 * back to returning the original value and log a warning rather than throwing — the plugin
 * lifecycle is driven by OIE and we have no reliable way to block on encryptor availability.
 */
public final class CredentialStore {

  private static final Logger logger = LogManager.getLogger(CredentialStore.class);

  /** Prefix marking a value as already encrypted, to make the save path idempotent. */
  public static final String ENCRYPTED_PREFIX = "{enc}";

  private CredentialStore() {}

  /** Returns {@code true} if the given value is already tagged as encrypted. */
  public static boolean isEncrypted(String value) {
    return value != null && value.startsWith(ENCRYPTED_PREFIX);
  }

  /**
   * Encrypts a plaintext credential. If the value is already encrypted, it is returned unchanged.
   * If the value is null or blank, it is returned as-is. If OIE's encryptor is not yet available,
   * the plaintext is returned with a warning logged — this should only happen during very early
   * startup.
   */
  public static String encrypt(String plaintext) {
    if (plaintext == null || plaintext.isEmpty() || isEncrypted(plaintext)) {
      return plaintext;
    }
    Encryptor encryptor = getEncryptor();
    if (encryptor == null) {
      logger.warn(
          "OIE encryptor not yet available; storing credential unencrypted. "
              + "Re-save the Git Sync settings once the server has fully started.");
      return plaintext;
    }
    try {
      return ENCRYPTED_PREFIX + encryptor.encrypt(plaintext);
    } catch (Exception e) {
      logger.error(
          "Failed to encrypt credential; falling back to plaintext. "
              + "This is a bug — please report it.",
          e);
      return plaintext;
    }
  }

  /**
   * Decrypts a credential previously produced by {@link #encrypt(String)}. Values without the
   * {@link #ENCRYPTED_PREFIX} are assumed to be plaintext (e.g. legacy stored values from before
   * this class existed) and returned unchanged.
   */
  public static String decrypt(String value) {
    if (value == null || value.isEmpty() || !isEncrypted(value)) {
      return value;
    }
    Encryptor encryptor = getEncryptor();
    if (encryptor == null) {
      logger.warn("OIE encryptor not yet available; cannot decrypt stored credential.");
      return "";
    }
    try {
      return encryptor.decrypt(value.substring(ENCRYPTED_PREFIX.length()));
    } catch (Exception e) {
      logger.error(
          "Failed to decrypt stored credential. "
              + "Re-enter the credential in Settings > Git Sync to recover.",
          e);
      return "";
    }
  }

  private static Encryptor getEncryptor() {
    try {
      return ConfigurationController.getInstance().getEncryptor();
    } catch (Exception e) {
      logger.debug("Could not obtain OIE encryptor", e);
      return null;
    }
  }
}
