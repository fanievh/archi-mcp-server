package net.vheerden.archi.mcp.response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IGrouping;

/**
 * Wiring pin between the two layers that each decide "is this diagram object a container?".
 *
 * <p>The model layer answers with an {@code instanceof} test against the element's concept
 * ({@code TopLevelGroupTargets.isTarget}). Above it, {@code handlers/} and {@code response/} are
 * forbidden to import EMF, so the DTO's type name is all they have — and that name is compared in
 * exactly one place, {@link ViewContainers}. This holds the two definitions together.
 *
 * <p>The string is not arbitrary: {@code ElementDto.type()} is {@code element.eClass().getName()},
 * so it is the EMF class name of the very type the model-layer predicate tests for. The first test
 * asserts that identity against what the ArchiMate factory actually produces at runtime rather than
 * restating the literal, so a rename in Archi's metamodel surfaces here instead of as a silently
 * empty container count. The third asserts the single-copy property itself, because the value of
 * one shared definition is lost the moment a second call site inlines its own.
 */
public class GroupingContainerTypeNameTest {

    private static final String SHARED_SOURCE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/response/ViewContainers.java";

    /**
     * The runtime half: the concept the model-layer predicate admits must present the exact name
     * the shared predicate tests for. Built from the factory, not from a literal.
     */
    @Test
    public void shouldNameTheGroupingConceptExactlyAsTheSharedPredicateTestsFor() {
        IGrouping grouping = IArchimateFactory.eINSTANCE.createGrouping();

        assertEquals("ViewContainers compares ElementDto.type(), which is eClass().getName(), "
                        + "against this literal — they must be the same string",
                "Grouping", grouping.eClass().getName());
        assertTrue("the shared predicate must admit the name the factory produces",
                ViewContainers.isContainerType(grouping.eClass().getName()));
    }

    /**
     * A host is not a zone. The arrangement family deliberately excludes element containers that
     * merely hold children, so the shared predicate must too — otherwise widening the reports would
     * promise containers no layout tool will ever reposition.
     */
    @Test
    public void shouldRejectElementTypesThatMerelyHoldChildren() {
        assertFalse(ViewContainers.isContainerType("ApplicationComponent"));
        assertFalse(ViewContainers.isContainerType("Node"));
        assertFalse("a null type must be safe, not a container",
                ViewContainers.isContainerType(null));
    }

    /**
     * The single-copy property. Three report formats ask this question; the moment one of them
     * inlines the literal instead of calling the shared predicate, the divergence this pin exists
     * to prevent is back, and this test is the only thing that would notice.
     */
    @Test
    public void shouldKeepExactlyOneCopyOfTheContainerTypeNameAboveTheModelLayer() {
        assertTrue("the shared definition must hold the type name",
                readRepoFile(SHARED_SOURCE).contains("GROUPING_ELEMENT_TYPE = \"Grouping\""));

        for (String consumer : new String[] {
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/handlers/ViewHandler.java",
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/response/SummaryFormatter.java"}) {
            assertFalse(consumer + " must ask ViewContainers rather than inlining the type name",
                    readRepoFile(consumer).contains("\"Grouping\""));
        }
    }

    private static String readRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("Could not read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " from "
                + Path.of("").toAbsolutePath());
    }
}
