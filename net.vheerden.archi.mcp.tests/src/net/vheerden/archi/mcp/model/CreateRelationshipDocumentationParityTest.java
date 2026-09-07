package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IFlowRelationship;

import net.vheerden.archi.mcp.response.dto.RelationshipDto;

/**
 * Pins that {@code create-relationship}'s two arms describe an empty {@code documentation} the same
 * way.
 *
 * <h2>The defect</h2>
 *
 * <p>A fresh create built its DTO by hand and mapped an empty documentation to null, so the key was
 * dropped; a create that happened to match an existing relationship routed through
 * {@code DtoMapper.convertToRelationshipDto(rel, true)}, whose mutation flavour deliberately
 * preserves {@code ""}. Same tool, identical inputs, two differently-shaped answers depending only
 * on whether the call happened to be the first one — and an agent that cannot see the model reads
 * an omitted key as "unchanged".</p>
 *
 * <h2>Why this class reads source text, which is normally the wrong thing to do</h2>
 *
 * <p>The fresh arm lives inside {@code prepareCreateRelationship}, behind Archi's own relationship
 * validation. That validation initialises {@code RelationshipsMatrix}, which resolves an OSGi
 * {@code Bundle} — measured, {@code Platform.getBundle(...)} returns null outside an OSGi platform
 * and the class-init throws:</p>
 *
 * <pre>
 * java.lang.ExceptionInInitializerError
 *   caused by java.lang.NullPointerException: Cannot invoke "org.osgi.framework.Bundle.getEntry(String)"
 *   because the return value of "org.eclipse.core.runtime.Platform.getBundle(String)" is null
 * </pre>
 *
 * <p>Neither automated lane supplies one: the headless lane has no OSGi at all, and the display lane
 * supplies a display, not a platform. Every existing accessor-level pin on relationship creation is
 * wrapped in an {@code Assume} for exactly this and therefore contributes nothing to either gate. So
 * an end-to-end assertion here would be a pin that never runs, which is worse than none — it reads
 * as coverage.</p>
 *
 * <p>What CAN be gated is the two halves of the agreement, separately and for real: the duplicate
 * arm's behaviour is asserted against the live mapper below, and the fresh arm's is asserted against
 * its source with comments stripped first, so a javadoc mentioning the old shape cannot satisfy or
 * break the check. Together they say the same thing an end-to-end test would: both arms hand the
 * caller whatever the model holds.</p>
 */
public class CreateRelationshipDocumentationParityTest {

    private static final String ACCESSOR =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java";

    /**
     * Half one, run for real: the duplicate arm's mapper preserves an empty documentation. This is
     * the behaviour the fresh arm has to match, so it is asserted rather than assumed.
     */
    @Test
    public void shouldPreserveAnEmptyDocumentation_onTheDuplicateArmsMapper() {
        IFlowRelationship rel = IArchimateFactory.eINSTANCE.createFlowRelationship();
        rel.setId("rel-1");
        rel.setName("flows to");
        rel.setDocumentation("");

        RelationshipDto dto = DtoMapper.convertToRelationshipDto(rel, true);

        assertEquals("the arm a duplicate hit takes reports what the model holds — empty, not absent",
                "", dto.documentation());
    }

    /**
     * Half two: the fresh arm passes the value it read straight into the DTO. The banned shape is
     * the emptiness strip that used to sit there; a bare {@code documentation} substring would be
     * satisfied by any of the several other mentions in this method and pin nothing.
     */
    @Test
    public void shouldNotStripAnEmptyDocumentation_onTheFreshCreateArm() {
        String source = freshCreateArmOf(readRepoFile(ACCESSOR));

        assertTrue("The fresh-create arm must hand the DTO the documentation it read, so a "
                + "relationship the model holds as empty is reported as empty — matching the arm a "
                + "duplicate takes. Found the old strip still in place.",
                !source.contains("effectiveDoc.isEmpty()"));
        assertTrue("and it must still read the value back off the EMF object rather than echoing "
                + "the caller's parameter",
                source.contains("String effectiveDoc = relationship.getDocumentation();"));
        assertTrue("the value must reach the DTO unconditionally",
                source.contains("false, effectiveDoc,"));
    }

    /**
     * The collection half is settled the other way and must not drift along with the scalar: an
     * emptied {@code properties} list stays absent on both arms. Pinned here beside the scalar
     * because this is where a future reader will come looking for "the empty-value rule".
     */
    @Test
    public void shouldStillOmitAnEmptyPropertiesList_onTheFreshCreateArm() {
        String source = freshCreateArmOf(readRepoFile(ACCESSOR));

        assertTrue("an empty properties list stays omitted — only the scalar changed",
                source.contains("effectiveProps.isEmpty() ? null : effectiveProps,"));
    }

    // ---- harness ------------------------------------------------------------------------------

    /**
     * The body of {@code prepareCreateRelationship} alone, comments stripped.
     *
     * <p>Scoping matters here in both directions. The accessor is ~17,900 lines and holds a second
     * local named {@code effectiveDoc} on the folder path, so a file-wide ban on
     * {@code effectiveDoc.isEmpty()} would go red the day that unrelated method grows an emptiness
     * guard of its own — a failure naming this relationship rule for a change that has nothing to do
     * with it. In the other direction, a file-wide *presence* check would pass on a matching line
     * anywhere in the file. Both assertions are therefore made against this one method.</p>
     */
    private String freshCreateArmOf(String accessorSource) {
        String stripped = strippedOfComments(accessorSource);
        List<String> matches = new ArrayList<>();
        for (String slice : stripped.split("(?=\n    (?:private|public|protected) )")) {
            String header = slice.strip();
            // Three overloads share this name; only one builds the DTO. Selected on the constructor
            // call rather than on anything the assertions below check, so the slice cannot be
            // defined by the very text it is then asked to confirm.
            if (header.startsWith("private PreparedMutation<RelationshipDto> prepareCreateRelationship(")
                    && slice.contains("new RelationshipDto(")) {
                matches.add(slice);
            }
        }
        if (matches.size() != 1) {
            throw new AssertionError("Expected exactly one prepareCreateRelationship overload that "
                    + "constructs a RelationshipDto; found " + matches.size() + ". Re-point this "
                    + "guard at the method that now builds the fresh-create DTO — do not delete it.");
        }
        return matches.get(0);
    }

    /**
     * Comments are removed before matching. A javadoc or a trailing note that quotes the old
     * expression would otherwise fail the ban, and one that quotes the new expression would satisfy
     * a presence check the code no longer earns.
     */
    private static String strippedOfComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\n]*", " ");
    }

    private String readRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the checkout.");
    }
}
