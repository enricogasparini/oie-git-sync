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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NodeRoleTest {

    @Nested
    @DisplayName("parse()")
    class Parse {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
                "CONTRIBUTOR, CONTRIBUTOR",
                "RECEIVER,    RECEIVER",
                "BOTH,        BOTH",
                "contributor, CONTRIBUTOR",
                "Receiver,    RECEIVER",
                "  BOTH  ,    BOTH"
        })
        void parsesValidValuesCaseInsensitivelyAndTrimmed(String input, NodeRole expected) {
            assertEquals(expected, NodeRole.parse(input));
        }

        @ParameterizedTest(name = "blank-like input \"{0}\" -> BOTH")
        @NullAndEmptySource
        @ValueSource(strings = { " ", "\t", "\n", "   " })
        void blankOrNullDefaultsToBoth(String input) {
            assertEquals(NodeRole.BOTH, NodeRole.parse(input));
        }

        @ParameterizedTest(name = "unknown \"{0}\" -> BOTH")
        @ValueSource(strings = { "ADMIN", "contributor-backup", "123", "BOTH!" })
        void unknownValueDefaultsToBoth(String input) {
            // The default is deliberately permissive: keep old configs working
            // across upgrades rather than failing fast on a typo.
            assertEquals(NodeRole.BOTH, NodeRole.parse(input));
        }
    }

    @Nested
    @DisplayName("isContributor() / isReceiver()")
    class Predicates {

        @Test
        void contributorIsContributorNotReceiver() {
            assertTrue(NodeRole.CONTRIBUTOR.isContributor());
            assertFalse(NodeRole.CONTRIBUTOR.isReceiver());
        }

        @Test
        void receiverIsReceiverNotContributor() {
            assertFalse(NodeRole.RECEIVER.isContributor());
            assertTrue(NodeRole.RECEIVER.isReceiver());
        }

        @Test
        void bothIsBoth() {
            assertTrue(NodeRole.BOTH.isContributor());
            assertTrue(NodeRole.BOTH.isReceiver());
        }
    }
}
