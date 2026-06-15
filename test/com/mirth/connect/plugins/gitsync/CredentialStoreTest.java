/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mirth.commons.encryption.Encryptor;
import com.mirth.connect.server.controllers.ConfigurationController;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class CredentialStoreTest {

    @Nested
    @DisplayName("isEncrypted()")
    class IsEncrypted {

        @Test
        void nullIsNotEncrypted() {
            assertFalse(CredentialStore.isEncrypted(null));
        }

        @Test
        void emptyIsNotEncrypted() {
            assertFalse(CredentialStore.isEncrypted(""));
        }

        @Test
        void plaintextIsNotEncrypted() {
            assertFalse(CredentialStore.isEncrypted("ghp_tokenvalue"));
            assertFalse(CredentialStore.isEncrypted("password123"));
        }

        @Test
        void enclosedPrefixIsEncrypted() {
            assertTrue(CredentialStore.isEncrypted("{enc}abcdef=="));
            assertTrue(CredentialStore.isEncrypted(CredentialStore.ENCRYPTED_PREFIX + "payload"));
        }

        @Test
        void caseSensitivityOfPrefix() {
            // Prefix matching is exact. An upper-case variant is not treated as
            // encrypted — catches any future accidental change to toLowerCase().
            assertFalse(CredentialStore.isEncrypted("{ENC}abcdef"));
            assertFalse(CredentialStore.isEncrypted("{Enc}abcdef"));
        }
    }

    @Nested
    @DisplayName("encrypt()")
    class Encrypt {

        @Test
        void nullPassesThrough() {
            assertNull(CredentialStore.encrypt(null));
        }

        @Test
        void emptyPassesThrough() {
            assertEquals("", CredentialStore.encrypt(""));
        }

        @Test
        void alreadyEncryptedIsIdempotent() {
            // The contract says encrypt() on an {enc}-tagged value returns it
            // unchanged so re-saving the settings form without editing the
            // password doesn't re-encrypt or drift the stored value.
            String tagged = CredentialStore.ENCRYPTED_PREFIX + "whatever";
            assertEquals(tagged, CredentialStore.encrypt(tagged));
        }

        @Test
        void plaintextIsEncryptedWithPrefixWhenEncryptorAvailable() throws Exception {
            Encryptor encryptor = mock(Encryptor.class);
            when(encryptor.encrypt("secret")).thenReturn("CIPHERTEXT");
            ConfigurationController cc = mock(ConfigurationController.class);
            when(cc.getEncryptor()).thenReturn(encryptor);
            try (MockedStatic<ConfigurationController> mocked =
                    mockStatic(ConfigurationController.class)) {
                mocked.when(ConfigurationController::getInstance).thenReturn(cc);

                String result = CredentialStore.encrypt("secret");

                assertEquals("{enc}CIPHERTEXT", result);
                verify(encryptor).encrypt("secret");
            }
        }

        @Test
        void plaintextPassesThroughWhenEncryptorIsNull() {
            // getEncryptor() may return null during early server startup before
            // the encryption subsystem has initialised. CredentialStore logs a
            // warning and returns the plaintext rather than throwing — the
            // caller will see an unencrypted value and the next save will
            // encrypt it.
            ConfigurationController cc = mock(ConfigurationController.class);
            when(cc.getEncryptor()).thenReturn(null);
            try (MockedStatic<ConfigurationController> mocked =
                    mockStatic(ConfigurationController.class)) {
                mocked.when(ConfigurationController::getInstance).thenReturn(cc);

                assertEquals("secret", CredentialStore.encrypt("secret"));
            }
        }

        @Test
        void plaintextPassesThroughWhenGetInstanceThrows() {
            // Belt-and-braces: if even ConfigurationController.getInstance()
            // blows up (unlikely but possible during bootstrapping), we fall
            // back to plaintext rather than propagating the exception up
            // through a save-credentials code path.
            try (MockedStatic<ConfigurationController> mocked =
                    mockStatic(ConfigurationController.class)) {
                mocked.when(ConfigurationController::getInstance)
                        .thenThrow(new RuntimeException("not ready"));

                assertEquals("secret", CredentialStore.encrypt("secret"));
            }
        }

        @Test
        void plaintextPassesThroughWhenEncryptorThrows() throws Exception {
            Encryptor encryptor = mock(Encryptor.class);
            when(encryptor.encrypt(anyString())).thenThrow(new RuntimeException("kaboom"));
            ConfigurationController cc = mock(ConfigurationController.class);
            when(cc.getEncryptor()).thenReturn(encryptor);
            try (MockedStatic<ConfigurationController> mocked =
                    mockStatic(ConfigurationController.class)) {
                mocked.when(ConfigurationController::getInstance).thenReturn(cc);

                // Encryption failure is logged at ERROR and the caller gets the
                // plaintext back. The next save will retry. Not ideal, but the
                // alternative is throwing during settings save and breaking
                // the admin console.
                assertEquals("secret", CredentialStore.encrypt("secret"));
            }
        }
    }

    @Nested
    @DisplayName("decrypt()")
    class Decrypt {

        @Test
        void nullPassesThrough() {
            assertNull(CredentialStore.decrypt(null));
        }

        @Test
        void emptyPassesThrough() {
            assertEquals("", CredentialStore.decrypt(""));
        }

        @Test
        void untaggedLegacyPlaintextPassesThrough() {
            // Backwards compatibility: values stored before the encryption
            // refactor do not have the {enc} prefix. decrypt() must return
            // them unchanged so existing installs keep working across the
            // upgrade. This is the single most important CredentialStore
            // test in terms of what it protects.
            assertEquals("ghp_legacyplaintext",
                    CredentialStore.decrypt("ghp_legacyplaintext"));
        }

        @Test
        void taggedValueIsDecryptedWhenEncryptorAvailable() throws Exception {
            Encryptor encryptor = mock(Encryptor.class);
            when(encryptor.decrypt("CIPHERTEXT")).thenReturn("secret");
            ConfigurationController cc = mock(ConfigurationController.class);
            when(cc.getEncryptor()).thenReturn(encryptor);
            try (MockedStatic<ConfigurationController> mocked =
                    mockStatic(ConfigurationController.class)) {
                mocked.when(ConfigurationController::getInstance).thenReturn(cc);

                String result = CredentialStore.decrypt("{enc}CIPHERTEXT");

                assertEquals("secret", result);
                verify(encryptor).decrypt("CIPHERTEXT");
            }
        }

        @Test
        void taggedValueReturnsEmptyWhenEncryptorIsNull() {
            // decrypt() returns "" (not the ciphertext) on missing encryptor
            // so the caller doesn't accidentally treat the encrypted blob as
            // a plaintext credential and attempt to authenticate with it.
            ConfigurationController cc = mock(ConfigurationController.class);
            when(cc.getEncryptor()).thenReturn(null);
            try (MockedStatic<ConfigurationController> mocked =
                    mockStatic(ConfigurationController.class)) {
                mocked.when(ConfigurationController::getInstance).thenReturn(cc);

                assertEquals("", CredentialStore.decrypt("{enc}CIPHERTEXT"));
            }
        }

        @Test
        void taggedValueReturnsEmptyWhenEncryptorThrows() throws Exception {
            Encryptor encryptor = mock(Encryptor.class);
            when(encryptor.decrypt(anyString())).thenThrow(new RuntimeException("bad key"));
            ConfigurationController cc = mock(ConfigurationController.class);
            when(cc.getEncryptor()).thenReturn(encryptor);
            try (MockedStatic<ConfigurationController> mocked =
                    mockStatic(ConfigurationController.class)) {
                mocked.when(ConfigurationController::getInstance).thenReturn(cc);

                assertEquals("", CredentialStore.decrypt("{enc}CIPHERTEXT"));
            }
        }
    }

    @Nested
    @DisplayName("Round-trip")
    class RoundTrip {

        @Test
        void encryptThenDecryptRecoversOriginal() throws Exception {
            // Simulates the full save + load cycle. The Encryptor mock uses
            // a trivial base64-like round-trip so we can assert symmetry
            // without depending on OIE's actual crypto.
            Encryptor encryptor = mock(Encryptor.class);
            when(encryptor.encrypt("s3cret!")).thenReturn("ENCs3cret!");
            when(encryptor.decrypt("ENCs3cret!")).thenReturn("s3cret!");
            ConfigurationController cc = mock(ConfigurationController.class);
            when(cc.getEncryptor()).thenReturn(encryptor);

            try (MockedStatic<ConfigurationController> mocked =
                    mockStatic(ConfigurationController.class)) {
                mocked.when(ConfigurationController::getInstance).thenReturn(cc);

                String stored = CredentialStore.encrypt("s3cret!");
                assertTrue(CredentialStore.isEncrypted(stored));
                String roundTripped = CredentialStore.decrypt(stored);
                assertEquals("s3cret!", roundTripped);
            }
        }

        @Test
        void encryptIsIdempotentAcrossMultipleSaves() throws Exception {
            // The scenario this protects against: user hits Save twice on
            // the settings panel without editing the password. First save
            // encrypts "hunter2" → "{enc}CIPHER". Second save sees the
            // {enc}-tagged value from the previous round-trip and must not
            // re-encrypt it to "{enc}ENC(CIPHER)".
            Encryptor encryptor = mock(Encryptor.class);
            when(encryptor.encrypt("hunter2")).thenReturn("CIPHER");
            ConfigurationController cc = mock(ConfigurationController.class);
            when(cc.getEncryptor()).thenReturn(encryptor);

            try (MockedStatic<ConfigurationController> mocked =
                    mockStatic(ConfigurationController.class)) {
                mocked.when(ConfigurationController::getInstance).thenReturn(cc);

                String first = CredentialStore.encrypt("hunter2");
                assertEquals("{enc}CIPHER", first);

                String second = CredentialStore.encrypt(first);
                assertEquals("{enc}CIPHER", second,
                        "Re-encrypting an already-tagged value must be a no-op");
            }
        }
    }
}
