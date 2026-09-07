package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Direct coverage for {@link ConceptMetadata#mergeSourceProperties}, the shared helper that folds a
 * caller's {@code source} traceability map into the {@code properties} map written onto a freshly
 * created element or relationship.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>The merge has four call sites — {@code create-element} and {@code create-relationship}, each on
 * its standalone path and again inside {@code bulk-mutate} — so a fix applied at one of them forks
 * the other three. It was previously a private method on the accessor facade, reachable only by
 * driving a tool end to end through a test class that needs a display; and the relationship-path
 * provenance pins there are assumption-skipped without an OSGi platform, so they gate nothing
 * automatically. As a package-visible static it is callable directly, in the headless lane, which
 * is where the rules below are pinned.</p>
 *
 * <h2>The two rules</h2>
 *
 * <p>Both are refusals, and both refuse for the same reason. A caller that cannot see the model has
 * only the response to go on, so a merge that silently drops a value it was handed, or silently
 * writes it under a key the caller did not ask for, produces a model whose provenance nobody can
 * later attribute — and produces it behind a success. Refusing names the offending key while the
 * caller still has it in hand.</p>
 */
public class ConceptMetadataTest {

    // ---------------------------------------------------------------------------------------
    // Rule 1 — a source key whose prefixed form collides with a caller's own property is refused,
    // rather than overwriting the value the caller set explicitly.
    // ---------------------------------------------------------------------------------------

    @Test
    public void shouldRefuseTheMerge_whenAPrefixedSourceKeyCollidesWithACallerProperty() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("mcp.source.file", "orders.csv");
        Map<String, String> source = new LinkedHashMap<>();
        source.put("file", "invoices.csv");

        try {
            Map<String, String> merged = ConceptMetadata.mergeSourceProperties(properties, source);
            fail("expected the collision to be refused, but the merge returned " + merged
                    + " — the caller's explicit 'mcp.source.file' value was silently replaced");
        } catch (ModelAccessException e) {
            assertEquals("a caller-typed key clash is a caller error, not a server fault",
                    ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("the message must name the property key that collides, so the caller can fix "
                    + "it without guessing which of the two maps to edit: " + e.getMessage(),
                    e.getMessage().contains("mcp.source.file"));
            assertTrue("and it must name the source entry that produced that key: " + e.getMessage(),
                    e.getMessage().contains("'file'"));
            assertNotNull("this server's error contract is structured errors with something to act "
                    + "on; a refusal with a null suggestedCorrection leaves the agent stuck",
                    e.getSuggestedCorrection());
        }
    }

    /**
     * The near-miss that must NOT be refused. A caller property named {@code file} and a source
     * entry named {@code file} produce two different property keys — {@code file} and
     * {@code mcp.source.file} — so nothing collides and both must survive. A guard that compared the
     * bare names would reject this legitimate pair.
     */
    @Test
    public void shouldMergeBoth_whenACallerPropertySharesOnlyTheUnprefixedName() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("file", "orders.csv");
        Map<String, String> source = new LinkedHashMap<>();
        source.put("file", "invoices.csv");

        Map<String, String> merged = ConceptMetadata.mergeSourceProperties(properties, source);

        assertEquals("the caller's own property is untouched", "orders.csv", merged.get("file"));
        assertEquals("and the source entry lands under the prefixed key",
                "invoices.csv", merged.get("mcp.source.file"));
    }

    // ---------------------------------------------------------------------------------------
    // Rule 2 — a source key that already carries the prefix is refused, rather than prefixed twice.
    // ---------------------------------------------------------------------------------------

    @Test
    public void shouldRefuseTheMerge_whenASourceKeyAlreadyCarriesThePrefix() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("mcp.source.tool", "import-script");

        try {
            Map<String, String> merged = ConceptMetadata.mergeSourceProperties(null, source);
            fail("expected an already-prefixed source key to be refused, but the merge returned "
                    + merged + " — the key was prefixed a second time");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("the message must name the offending key: " + e.getMessage(),
                    e.getMessage().contains("mcp.source.tool"));
            assertNotNull("a refusal owes the caller a correction to make",
                    e.getSuggestedCorrection());
            assertTrue("and the correction must name the bare key to use instead: "
                    + e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("tool"));
        }
    }

    /**
     * The degenerate key: exactly the prefix, with nothing after it. It is refused like any other
     * already-prefixed key, but the generic remedy would tell the caller to "pass the bare key"
     * and then quote the empty string — advice that rebuilds the very key being refused, since
     * prefix + "" is the key again. The remedy has to name something the caller can actually send.
     */
    @Test
    public void shouldNotSuggestAnEmptyBareKey_whenTheSourceKeyIsExactlyThePrefix() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("mcp.source.", "import-script");

        try {
            ConceptMetadata.mergeSourceProperties(null, source);
            fail("a key that is exactly the prefix must still be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            String correction = e.getSuggestedCorrection();
            assertNotNull(correction);
            assertFalse("the remedy must not quote an empty bare key — following it would rebuild "
                    + "the refused key: " + correction, correction.contains("key — '' "));
            assertTrue("it must name an attribute the caller can actually send instead: "
                    + correction, correction.contains("names nothing after it"));
        }
    }

    /**
     * The double-prefixed key is what the refusal exists to prevent, so pin its absence directly:
     * no path through the merge may ever produce one.
     */
    @Test
    public void shouldNeverProduceADoublePrefixedKey_forAnyAcceptedSourceMap() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("tool", "import-script");
        source.put("repository", "arch-mcp-server");

        Map<String, String> merged = ConceptMetadata.mergeSourceProperties(null, source);

        for (String key : merged.keySet()) {
            assertTrue("no merged key may carry the prefix twice: " + key,
                    !key.startsWith("mcp.source.mcp.source."));
        }
        assertEquals("import-script", merged.get("mcp.source.tool"));
        assertEquals("arch-mcp-server", merged.get("mcp.source.repository"));
    }

    // ---------------------------------------------------------------------------------------
    // Negative controls — the ordinary merge is unchanged. A refusal that fired on a clean call
    // would be a worse defect than the one it replaced.
    // ---------------------------------------------------------------------------------------

    @Test
    public void shouldReturnThePropertiesUnchanged_whenNoSourceMapIsGiven() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("owner", "platform-team");

        assertEquals("a null source map is the common case and must not allocate a new map",
                properties, ConceptMetadata.mergeSourceProperties(properties, null));
        assertEquals("an empty source map is treated the same way",
                properties, ConceptMetadata.mergeSourceProperties(properties, Map.of()));
        assertNull("and null properties with no source stays null",
                ConceptMetadata.mergeSourceProperties(null, null));
    }

    @Test
    public void shouldPrefixEverySourceEntry_andPreserveCallerPropertiesAndOrder() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("owner", "platform-team");
        Map<String, String> source = new LinkedHashMap<>();
        source.put("tool", "import-script");
        source.put("file", "orders.csv");

        Map<String, String> merged = ConceptMetadata.mergeSourceProperties(properties, source);

        assertEquals("caller properties first, then source entries in iteration order",
                java.util.List.of("owner", "mcp.source.tool", "mcp.source.file"),
                java.util.List.copyOf(merged.keySet()));
        assertEquals("platform-team", merged.get("owner"));
        assertEquals("import-script", merged.get("mcp.source.tool"));
        assertEquals("orders.csv", merged.get("mcp.source.file"));
    }

    /**
     * A null key or null value in the source map is skipped rather than refused. That predates both
     * rules above and is not what they are about — a skipped null writes nothing under a key the
     * caller can be surprised by, which is the harm the refusals address.
     */
    @Test
    public void shouldSkipNullKeysAndValues_asItAlwaysHas() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("tool", null);
        source.put(null, "import-script");
        source.put("file", "orders.csv");

        Map<String, String> merged = ConceptMetadata.mergeSourceProperties(null, source);

        assertEquals("only the complete entry survives",
                Map.of("mcp.source.file", "orders.csv"), merged);
    }
}
