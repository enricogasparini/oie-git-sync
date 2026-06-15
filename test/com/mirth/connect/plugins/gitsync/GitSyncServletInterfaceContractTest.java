/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.plugins.gitsync.model.GitSyncStatus;
import com.mirth.connect.plugins.gitsync.model.PendingChange;
import com.mirth.connect.plugins.gitsync.model.PendingChangeList;
import com.mirth.connect.plugins.gitsync.model.PromotionRequest;
import com.mirth.connect.plugins.gitsync.model.PromotionResult;
import com.mirth.connect.plugins.gitsync.model.SyncRecord;
import com.thoughtworks.xstream.annotations.XStreamAlias;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Annotation contract tests for {@link GitSyncServletInterface}. These
 * exercise the JAX-RS and OIE extension-API metadata rather than any
 * runtime behaviour. They catch the class of regression where a new
 * endpoint is added to the interface but its @Path / @MirthOperation /
 * permission is forgotten, or a typo in a path silently breaks every
 * caller.
 *
 * Runtime HTTP correctness is validated by the live container tests on
 * engine-oie-1 and engine-oie-prod-1 (see the refactor branch test plan);
 * spinning up an embedded JAX-RS server in a unit test would require
 * mocking the entire MirthServlet base class and pulls in more surface
 * than it protects.
 */
class GitSyncServletInterfaceContractTest {

    private static final Set<String> KNOWN_PERMISSIONS = Set.of(
            GitSyncServletInterface.PERMISSION_VIEW,
            GitSyncServletInterface.PERMISSION_MANAGE,
            GitSyncServletInterface.PERMISSION_PROMOTE);

    private static List<Method> apiMethods() {
        // Every declared method is an API endpoint — the interface has no
        // default methods. Filter to the obvious set and skip anything the
        // compiler synthesises.
        return Arrays.stream(GitSyncServletInterface.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .toList();
    }

    @Test
    @DisplayName("Interface is annotated with base @Path, @Consumes, @Produces")
    void interfaceLevelAnnotationsArePresent() {
        Path classPath = GitSyncServletInterface.class.getAnnotation(Path.class);
        Consumes consumes = GitSyncServletInterface.class.getAnnotation(Consumes.class);
        Produces produces = GitSyncServletInterface.class.getAnnotation(Produces.class);

        assertNotNull(classPath, "Interface must carry a @Path annotation");
        assertEquals("/extensions/gitsync", classPath.value());
        assertNotNull(consumes, "Interface must carry @Consumes");
        assertTrue(consumes.value().length > 0);
        assertNotNull(produces, "Interface must carry @Produces");
        assertTrue(produces.value().length > 0);
    }

    @Test
    @DisplayName("Every API method has @Path")
    void everyMethodHasAPath() {
        for (Method m : apiMethods()) {
            assertNotNull(m.getAnnotation(Path.class),
                    "Missing @Path on " + m.getName());
        }
    }

    @Test
    @DisplayName("Every API method has exactly one HTTP verb annotation (@GET or @POST)")
    void everyMethodHasAnHttpVerb() {
        for (Method m : apiMethods()) {
            int verbs = 0;
            if (m.getAnnotation(GET.class) != null) {
                verbs++;
            }
            if (m.getAnnotation(POST.class) != null) {
                verbs++;
            }
            assertEquals(1, verbs,
                    "Exactly one of @GET / @POST required on " + m.getName()
                            + ", got " + verbs);
        }
    }

    @Test
    @DisplayName("Every API method has @MirthOperation with a known permission")
    void everyMethodHasMirthOperationWithKnownPermission() {
        for (Method m : apiMethods()) {
            MirthOperation op = m.getAnnotation(MirthOperation.class);
            assertNotNull(op, "Missing @MirthOperation on " + m.getName());
            assertNotNull(op.name());
            assertFalse(op.name().isEmpty(),
                    "@MirthOperation.name() must not be empty on " + m.getName());
            assertNotNull(op.display());
            assertFalse(op.display().isEmpty(),
                    "@MirthOperation.display() must not be empty on " + m.getName());
            assertTrue(KNOWN_PERMISSIONS.contains(op.permission()),
                    m.getName() + " references unknown permission '"
                            + op.permission() + "', expected one of " + KNOWN_PERMISSIONS);
        }
    }

    @Test
    @DisplayName("@Path values are unique per HTTP method")
    void noTwoMethodsShareTheSameVerbAndPath() {
        Set<String> seen = new HashSet<>();
        for (Method m : apiMethods()) {
            String verb = m.getAnnotation(GET.class) != null ? "GET" : "POST";
            String path = m.getAnnotation(Path.class).value();
            String key = verb + " " + path;
            assertTrue(seen.add(key),
                    "Duplicate endpoint route: " + key
                            + " (two interface methods share the same HTTP method and path)");
        }
    }

    @Test
    @DisplayName("All @MirthOperation.name() values are unique")
    void mirthOperationNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Method m : apiMethods()) {
            MirthOperation op = m.getAnnotation(MirthOperation.class);
            String name = op.name();
            assertTrue(seen.add(name),
                    "Two methods share @MirthOperation name '" + name
                            + "' — the OIE RBAC subsystem uses this as a unique key");
        }
    }

    @Test
    @DisplayName("All REST-facing model DTOs carry @XStreamAlias")
    void allModelDtosHaveXStreamAlias() {
        List<Class<?>> dtoClasses = List.of(
                GitSyncStatus.class,
                PendingChange.class,
                PendingChangeList.class,
                PromotionRequest.class,
                PromotionResult.class,
                SyncRecord.class);

        for (Class<?> dto : dtoClasses) {
            XStreamAlias alias = dto.getAnnotation(XStreamAlias.class);
            assertNotNull(alias,
                    dto.getSimpleName() + " is missing @XStreamAlias — REST XML will use FQCN");
            assertFalse(alias.value().isEmpty(),
                    dto.getSimpleName() + " has empty @XStreamAlias value");
        }
    }

    @Test
    @DisplayName("Baseline endpoint inventory matches the documented REST API")
    void expectedEndpointsArePresent() {
        // The README's REST API Reference table lists every endpoint; keep
        // this test in sync with that list so adding a new endpoint to the
        // interface without updating the docs (or vice versa) shows up.
        Set<String> expectedPathAndVerb = Set.of(
                "GET /status",
                "POST /_sync",
                "GET /log",
                "POST /_testConnection",
                "POST /_resetLocalRepo",
                "POST /promote",
                "POST /promote/preview",
                "GET /pending",
                "POST /pending/commit",
                "POST /pending/discard");
        Set<String> actual = new HashSet<>();
        for (Method m : apiMethods()) {
            String verb = m.getAnnotation(GET.class) != null ? "GET" : "POST";
            String path = m.getAnnotation(Path.class).value();
            actual.add(verb + " " + path);
        }

        assertAll(
                () -> assertTrue(actual.containsAll(expectedPathAndVerb),
                        "Missing endpoints: "
                                + Set.copyOf(expectedPathAndVerb).stream()
                                        .filter(e -> !actual.contains(e))
                                        .toList()),
                () -> assertTrue(expectedPathAndVerb.containsAll(actual),
                        "Undocumented endpoints present on the interface: "
                                + actual.stream()
                                        .filter(e -> !expectedPathAndVerb.contains(e))
                                        .toList()
                                + " — update the README and this test"));
    }
}
