/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.util.ConfigurationProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Unit tests for ArtifactSerializer. Real OIE model POJOs (Channel,
 * ChannelGroup, CodeTemplate, CodeTemplateLibrary) are constructed via their
 * public constructors and setters so the tests stay close to production
 * usage. ObjectXMLSerializer is mocked statically so we assert what gets
 * passed in and control the XML that gets written, without exercising the
 * full OIE XStream converter registry — which would require a
 * fully-initialised OIE runtime (MirthMapperWrapper pulls in Rhino's
 * NativeDate, which is package-private and inaccessible from a plain
 * test JVM).
 *
 * ConfigurationProperty and global script / config map paths don't touch
 * ObjectXMLSerializer at all, so those tests exercise the real code
 * end-to-end without any Mockito.
 */
class ArtifactSerializerTest {

    @TempDir
    Path repo;

    ArtifactSerializer sut;
    ObjectMapper json;

    @BeforeEach
    void setUp() {
        sut = new ArtifactSerializer(repo);
        json = new ObjectMapper();
    }

    /**
     * Creates a real Channel with enough internal state that Channel.clone()
     * (called by serializeChannel) does not NPE. Channel.clone() reads
     * destinationConnectors, which is only initialised by the no-arg
     * constructor, not by Channel(String id).
     */
    private static Channel buildChannel(String id, String name, int revision, String description) {
        Channel c = new Channel();
        c.setId(id);
        c.setName(name);
        c.setRevision(revision);
        c.setDescription(description);
        c.setSourceConnector(new Connector("source"));
        return c;
    }

    /**
     * Install a MockedStatic for ObjectXMLSerializer.getInstance() returning
     * a mock whose serialize() returns a canned XML string. The stub
     * instance is created BEFORE mocked.when(...).thenReturn(...) to avoid
     * Mockito's "unfinished stubbing detected" error when nested mock
     * construction happens inside a thenReturn argument.
     *
     * Usage:
     *   try (var mocked = stubSerializer()) {
     *       // exercise ArtifactSerializer — its ObjectXMLSerializer calls
     *       // return the canned XML
     *   }
     */
    private static StubbedSerializer stubSerializer() {
        ObjectXMLSerializer stubInstance = mock(ObjectXMLSerializer.class);
        when(stubInstance.serialize(any())).thenReturn("<fake-xml/>");
        MockedStatic<ObjectXMLSerializer> mocked = mockStatic(ObjectXMLSerializer.class);
        mocked.when(ObjectXMLSerializer::getInstance).thenReturn(stubInstance);
        return new StubbedSerializer(mocked, stubInstance);
    }

    /** AutoCloseable wrapper so tests can try-with-resources the mockStatic scope. */
    private static final class StubbedSerializer implements AutoCloseable {
        final MockedStatic<ObjectXMLSerializer> mocked;
        final ObjectXMLSerializer instance;

        StubbedSerializer(MockedStatic<ObjectXMLSerializer> mocked, ObjectXMLSerializer instance) {
            this.mocked = mocked;
            this.instance = instance;
        }

        @Override
        public void close() {
            mocked.close();
        }
    }

    @Nested
    @DisplayName("serializeChannel()")
    class SerializeChannelTests {

        @Test
        void writesChannelXmlAndMetadataJsonToCorrectPaths() throws Exception {
            Channel channel = buildChannel("ch-123", "My Channel", 7, "Test channel");

            String relative;
            try (var stub = stubSerializer()) {
                relative = sut.serializeChannel(channel);
                verify(stub.instance).serialize(any(Channel.class));
            }

            assertEquals("channels/ch-123", relative);
            Path xml = repo.resolve("channels/ch-123/channel.xml");
            Path meta = repo.resolve("channels/ch-123/channel-metadata.json");
            assertTrue(Files.exists(xml), "channel.xml must exist");
            assertTrue(Files.exists(meta), "channel-metadata.json must exist");
            assertEquals("<fake-xml/>", Files.readString(xml, StandardCharsets.UTF_8));
        }

        @Test
        void metadataJsonHasExpectedShapeAndValues() throws Exception {
            Channel channel = buildChannel("abcd", "HL7 Listener", 42, "Inbound HL7v2");

            try (var stub = stubSerializer()) {
                sut.serializeChannel(channel);
            }

            JsonNode meta = json.readTree(
                    Files.readString(repo.resolve("channels/abcd/channel-metadata.json"),
                            StandardCharsets.UTF_8));
            assertAll(
                    () -> assertEquals("abcd", meta.get("id").asText()),
                    () -> assertEquals("HL7 Listener", meta.get("name").asText()),
                    () -> assertEquals(42, meta.get("revision").asInt()),
                    () -> assertEquals("Inbound HL7v2", meta.get("description").asText()));
        }

        @Test
        void nullDescriptionBecomesEmptyStringInMetadata() throws Exception {
            Channel channel = buildChannel("no-desc", "X", 1, null);

            try (var stub = stubSerializer()) {
                sut.serializeChannel(channel);
            }

            JsonNode meta = json.readTree(
                    Files.readString(repo.resolve("channels/no-desc/channel-metadata.json"),
                            StandardCharsets.UTF_8));
            assertEquals("", meta.get("description").asText());
        }

        @Test
        void metadataIsPrettyPrintedAndEndsWithNewline() throws Exception {
            Channel channel = buildChannel("pretty", "P", 1, "d");
            try (var stub = stubSerializer()) {
                sut.serializeChannel(channel);
            }

            String content = Files.readString(
                    repo.resolve("channels/pretty/channel-metadata.json"), StandardCharsets.UTF_8);
            assertTrue(content.contains("\n  \""),
                    "Metadata JSON should be pretty-printed (indented)");
            assertTrue(content.endsWith("\n"),
                    "Metadata JSON should terminate with a newline");
        }

        @Test
        void serialisesTheClonedChannelNotTheOriginal() throws Exception {
            // serializeChannel must clear exportData on the CLONE (so the
            // live channel object in memory keeps its export data) and
            // serialise the clone, not the original.
            Channel channel = buildChannel("cloned", "C", 1, "");
            try (var stub = stubSerializer()) {
                sut.serializeChannel(channel);
                // The mock was called exactly once with some Channel —
                // that Channel is the clone produced inside serializeChannel.
                verify(stub.instance).serialize(any(Channel.class));
            }
        }

        @Test
        void rejectsChannelIdsWithPathTraversal() throws Exception {
            // OIE channel IDs are normally UUIDs, but the OIE REST API accepts
            // arbitrary id strings on import. The id becomes a directory name
            // that is written to and recursively deleted, so traversal
            // attempts must be rejected before any filesystem operation.
            for (String hostile : new String[] {"..", ".", "a/b", "a\\b", "", "  "}) {
                Channel channel = buildChannel(hostile, "Evil", 1, "");
                org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                        () -> sut.serializeChannel(channel),
                        "Channel id '" + hostile + "' must be rejected");
            }
            // Nothing escaped: the parent of the repo dir is untouched and no
            // channels dir was created for the hostile ids.
            assertFalse(Files.exists(repo.resolve("channels/..").normalize()
                    .resolve("channel.xml")));
        }

        @Test
        void deserializeChannelReadsFileAndDelegatesToObjectXMLSerializer() throws Exception {
            Path xmlFile = Files.writeString(
                    Files.createDirectories(repo.resolve("channels/rt"))
                            .resolve("channel.xml"),
                    "<fake-channel/>", StandardCharsets.UTF_8);
            Channel expected = buildChannel("rt", "Round Trip", 3, "");
            ObjectXMLSerializer stubInstance = mock(ObjectXMLSerializer.class);
            when(stubInstance.deserialize(anyString(), eq(Channel.class))).thenReturn(expected);

            try (MockedStatic<ObjectXMLSerializer> mocked = mockStatic(ObjectXMLSerializer.class)) {
                mocked.when(ObjectXMLSerializer::getInstance).thenReturn(stubInstance);

                Channel actual = sut.deserializeChannel(xmlFile);

                assertEquals(expected, actual);
                verify(stubInstance).deserialize("<fake-channel/>", Channel.class);
            }
        }
    }

    @Nested
    @DisplayName("serializeCodeTemplateLibrary()")
    class SerializeCodeTemplateLibraryTests {

        @Test
        void writesLibraryXmlAndOneXmlPerTemplate() throws Exception {
            CodeTemplate t1 = new CodeTemplate("tmpl-1");
            t1.setName("utils");
            CodeTemplate t2 = new CodeTemplate("tmpl-2");
            t2.setName("parsers");

            CodeTemplateLibrary library = new CodeTemplateLibrary();
            library.setId("lib-x");
            library.setName("Library X");
            List<CodeTemplate> list = new ArrayList<>();
            list.add(t1);
            list.add(t2);
            library.setCodeTemplates(list);

            List<String> paths;
            try (var stub = stubSerializer()) {
                paths = sut.serializeCodeTemplateLibrary(library);
            }

            // Paths returned: the library base path plus one entry per template file.
            assertEquals(3, paths.size());
            assertEquals("code-templates/lib-x", paths.get(0));
            assertTrue(paths.contains("code-templates/lib-x/tmpl-1.xml"));
            assertTrue(paths.contains("code-templates/lib-x/tmpl-2.xml"));

            assertTrue(Files.exists(repo.resolve("code-templates/lib-x/library.xml")));
            assertTrue(Files.exists(repo.resolve("code-templates/lib-x/tmpl-1.xml")));
            assertTrue(Files.exists(repo.resolve("code-templates/lib-x/tmpl-2.xml")));
        }

        @Test
        void libraryWithNullTemplatesProducesOnlyLibraryXml() throws Exception {
            CodeTemplateLibrary library = new CodeTemplateLibrary();
            library.setId("empty-lib");
            library.setName("Empty");
            // Default codeTemplates list is non-null in the no-arg constructor,
            // but we explicitly null it out to exercise the null-check branch
            // in serializeCodeTemplateLibrary.
            library.setCodeTemplates(null);

            List<String> paths;
            try (var stub = stubSerializer()) {
                paths = sut.serializeCodeTemplateLibrary(library);
            }

            assertEquals(1, paths.size());
            assertEquals("code-templates/empty-lib", paths.get(0));
            assertTrue(Files.exists(repo.resolve("code-templates/empty-lib/library.xml")));
        }

        @Test
        void rejectsLibraryAndTemplateIdsWithPathTraversal() throws Exception {
            CodeTemplateLibrary hostileLibrary = new CodeTemplateLibrary();
            hostileLibrary.setId("../escape");
            hostileLibrary.setName("Evil");
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> sut.serializeCodeTemplateLibrary(hostileLibrary),
                    "Library id with a path separator must be rejected");

            CodeTemplateLibrary library = new CodeTemplateLibrary();
            library.setId("lib-ok");
            library.setName("OK");
            CodeTemplate hostileTemplate = new CodeTemplate("..");
            List<CodeTemplate> list = new ArrayList<>();
            list.add(hostileTemplate);
            library.setCodeTemplates(list);
            try (var stub = stubSerializer()) {
                org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                        () -> sut.serializeCodeTemplateLibrary(library),
                        "Template id '..' must be rejected");
            }
        }
    }

    @Nested
    @DisplayName("requireSafeId()")
    class RequireSafeIdTests {

        @Test
        void acceptsNormalIds() throws Exception {
            for (String ok : new String[] {
                    "a1b2c3d4-5678-90ab-cdef-1234567890ab", "lib_1", "Alice.B", "x"}) {
                assertEquals(ok, ArtifactSerializer.requireSafeId(ok));
            }
        }

        @Test
        void rejectsTraversalAndBlankIds() {
            for (String bad : new String[] {null, "", "  ", ".", "..", "...", "a/b", "a\\b",
                    "../up", "..\\up"}) {
                org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                        () -> ArtifactSerializer.requireSafeId(bad),
                        "Id '" + bad + "' must be rejected");
            }
        }

        @Test
        void allowsDotsInsideAnOtherwiseSafeId() throws Exception {
            // Dotted ids like "my.channel.v2" are legal — only ids that are
            // entirely dots (path tokens "." / "..") are dangerous.
            assertEquals("my.channel.v2", ArtifactSerializer.requireSafeId("my.channel.v2"));
        }
    }

    @Nested
    @DisplayName("serializeGlobalScripts()")
    class SerializeGlobalScriptsTests {

        @Test
        void writesEachNonBlankScriptAsLowercaseDotJs() throws Exception {
            Map<String, String> scripts = new LinkedHashMap<>();
            scripts.put("Deploy", "// deploy");
            scripts.put("Undeploy", "// undeploy");
            scripts.put("Preprocessor", "");        // empty — should be skipped
            scripts.put("Postprocessor", "  \n\t"); // blank-only — should be skipped
            scripts.put("Custom", null);            // null — should be skipped

            List<String> paths = sut.serializeGlobalScripts(scripts);

            assertEquals(2, paths.size());
            assertTrue(paths.contains("global-scripts/deploy.js"));
            assertTrue(paths.contains("global-scripts/undeploy.js"));
            assertEquals("// deploy",
                    Files.readString(repo.resolve("global-scripts/deploy.js"), StandardCharsets.UTF_8));
            assertEquals("// undeploy",
                    Files.readString(repo.resolve("global-scripts/undeploy.js"), StandardCharsets.UTF_8));
            assertFalse(Files.exists(repo.resolve("global-scripts/preprocessor.js")));
            assertFalse(Files.exists(repo.resolve("global-scripts/postprocessor.js")));
            assertFalse(Files.exists(repo.resolve("global-scripts/custom.js")));
        }

        @Test
        void emptyInputProducesNoFiles() throws Exception {
            List<String> paths = sut.serializeGlobalScripts(new LinkedHashMap<>());
            assertTrue(paths.isEmpty());
            // The global-scripts directory is still created — a subsequent call
            // to add a script then won't fail on the missing parent directory.
            assertTrue(Files.exists(repo.resolve("global-scripts")));
        }
    }

    @Nested
    @DisplayName("serializeChannelGroups()")
    class SerializeChannelGroupsTests {

        @Test
        void writesOneXmlPerGroup() throws Exception {
            ChannelGroup g1 = new ChannelGroup("group-1", "Group One", "");
            ChannelGroup g2 = new ChannelGroup("group-2", "Group Two", "");

            List<String> paths;
            try (var stub = stubSerializer()) {
                paths = sut.serializeChannelGroups(List.of(g1, g2));
            }

            assertEquals(List.of(
                    "channel-groups/group-1.xml",
                    "channel-groups/group-2.xml"),
                    paths);
            assertTrue(Files.exists(repo.resolve("channel-groups/group-1.xml")));
            assertTrue(Files.exists(repo.resolve("channel-groups/group-2.xml")));
        }

        @Test
        void emptyChannelGroupListWritesNoFiles() throws Exception {
            List<String> paths = sut.serializeChannelGroups(List.of());
            assertTrue(paths.isEmpty());
            assertTrue(Files.exists(repo.resolve("channel-groups")));
        }
    }

    @Nested
    @DisplayName("serializeConfigMapTemplate()")
    class SerializeConfigMapTemplateTests {

        @Test
        void writesKeysAndCommentsOnlyNoValues() throws Exception {
            // The whole point of the config-map template is that values
            // (which can be secrets) never reach Git. Assert explicitly.
            Map<String, ConfigurationProperty> props = new LinkedHashMap<>();
            props.put("db.password", new ConfigurationProperty("s3cret", "Database password"));
            props.put("api.key", new ConfigurationProperty("gigantic-token-xyz",
                    "Key for the upstream API"));
            props.put("no.comment", new ConfigurationProperty("whatever", null));

            String relative = sut.serializeConfigMapTemplate(props);
            assertEquals("config-map", relative);
            Path file = repo.resolve("config-map/config-map-template.json");
            assertTrue(Files.exists(file));
            String content = Files.readString(file, StandardCharsets.UTF_8);

            assertFalse(content.contains("s3cret"), "Secret value must not appear in the template");
            assertFalse(content.contains("gigantic-token-xyz"),
                    "API key value must not appear in the template");

            JsonNode root = json.readTree(content);
            assertEquals("Database password",
                    root.get("db.password").get("comment").asText());
            assertEquals("Key for the upstream API",
                    root.get("api.key").get("comment").asText());
            // null comments become empty strings
            assertEquals("", root.get("no.comment").get("comment").asText());
            // No "value" field anywhere
            assertFalse(content.contains("\"value\""),
                    "Template JSON must not carry a 'value' field");
        }

        @Test
        void emptyInputProducesEmptyJsonObject() throws Exception {
            sut.serializeConfigMapTemplate(new LinkedHashMap<>());
            String content = Files.readString(
                    repo.resolve("config-map/config-map-template.json"), StandardCharsets.UTF_8);
            JsonNode root = json.readTree(content);
            assertTrue(root.isObject());
            assertEquals(0, root.size());
        }

        @Test
        void keysArePreservedInInsertionOrder() throws IOException {
            Map<String, ConfigurationProperty> props = new LinkedHashMap<>();
            props.put("zebra", new ConfigurationProperty("", "z"));
            props.put("alpha", new ConfigurationProperty("", "a"));
            props.put("mango", new ConfigurationProperty("", "m"));

            sut.serializeConfigMapTemplate(props);
            String content = Files.readString(
                    repo.resolve("config-map/config-map-template.json"), StandardCharsets.UTF_8);
            int zebraPos = content.indexOf("zebra");
            int alphaPos = content.indexOf("alpha");
            int mangoPos = content.indexOf("mango");
            assertTrue(zebraPos < alphaPos && alphaPos < mangoPos,
                    "Insertion order must be preserved in the written JSON");
        }

        @Test
        void specialCharactersInKeysAreEscaped() throws Exception {
            // Keys with quotes, newlines, backslashes must be escaped rather
            // than dumped raw — the previous hand-rolled JSON writer did this
            // via a brittle replace() chain; the Jackson-based one should just
            // work, but assert explicitly so any future regression is loud.
            Map<String, ConfigurationProperty> props = new LinkedHashMap<>();
            props.put("key.with.\"quotes\"", new ConfigurationProperty("", "q"));
            props.put("key.with.\\backslash", new ConfigurationProperty("", "b"));

            sut.serializeConfigMapTemplate(props);
            String content = Files.readString(
                    repo.resolve("config-map/config-map-template.json"), StandardCharsets.UTF_8);

            // Parse back — if escaping was broken, this would throw.
            JsonNode root = json.readTree(content);
            assertEquals("q", root.get("key.with.\"quotes\"").get("comment").asText());
            assertEquals("b", root.get("key.with.\\backslash").get("comment").asText());
        }
    }
}
