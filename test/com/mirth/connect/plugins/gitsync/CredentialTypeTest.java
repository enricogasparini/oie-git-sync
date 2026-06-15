/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CredentialTypeTest {

    @Nested
    @DisplayName("parse()")
    class Parse {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
                "NONE,        NONE",
                "HTTPS_TOKEN, HTTPS_TOKEN",
                "HTTPS_BASIC, HTTPS_BASIC",
                "SSH_KEY,     SSH_KEY",
                "https_token, HTTPS_TOKEN",
                "None,        NONE",
                "  SSH_KEY ,  SSH_KEY"
        })
        void parsesValidValuesCaseInsensitivelyAndTrimmed(String input, CredentialType expected) {
            assertEquals(expected, CredentialType.parse(input));
        }

        @ParameterizedTest(name = "blank-like input \"{0}\" -> NONE")
        @NullAndEmptySource
        @ValueSource(strings = { " ", "\t", "\n", "   " })
        void blankOrNullDefaultsToNone(String input) {
            assertEquals(CredentialType.NONE, CredentialType.parse(input));
        }

        @ParameterizedTest(name = "unknown \"{0}\" -> NONE")
        @ValueSource(strings = { "OAUTH", "GPG_KEY", "HTTPS", "ssh_password" })
        void unknownValueDefaultsToNone(String input) {
            // Same permissive-fallback rationale as NodeRole: prefer "no
            // credential" over throwing at startup on an unexpected value.
            assertEquals(CredentialType.NONE, CredentialType.parse(input));
        }
    }

    @Nested
    @DisplayName("requiresUsernamePassword()")
    class RequiresUsernamePassword {

        @ParameterizedTest
        @EnumSource(value = CredentialType.class, names = { "HTTPS_TOKEN", "HTTPS_BASIC" })
        void trueForHttpsSchemes(CredentialType type) {
            assertTrue(type.requiresUsernamePassword(),
                    type + " uses username + password/token, field should be required");
        }

        @Test
        void falseForNone() {
            assertFalse(CredentialType.NONE.requiresUsernamePassword());
        }

        @Test
        void falseForSshKey() {
            // SSH_KEY delegates to the system SSH agent / default identity,
            // there is no in-plugin username+password flow.
            assertFalse(CredentialType.SSH_KEY.requiresUsernamePassword());
        }
    }
}
