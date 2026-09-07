package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.gef.commands.Command;
import org.junit.Test;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.model.exceptions.MutationException;

/**
 * Headless tests for the {@link ProposalBuilder} — the shared "stored request → fresh command +
 * preconditions" rebuilder. Verifies that a proposal's deferred rebuild
 * handle is re-invoked to produce a fresh {@link PreparedMutation}, and that a missing/incompatible target
 * surfaces as a clean {@link MutationException} (stale) — never an NPE or a raw exception.
 */
public class ProposalBuilderTest {

    private final ProposalBuilder builder = new ProposalBuilder();

    @Test
    public void shouldRebuildFreshCommand_fromDeferredHandle() {
        Command cmd = new NoOp("Update element: id-1");
        PendingProposal p = proposal(() -> new PreparedMutation<>(cmd, "entityDto", "id-1"));

        PreparedMutation<?> fresh = builder.rebuild(p);

        assertSame("the handle's command is returned", cmd, fresh.command());
        assertEquals("entityDto", fresh.entity());
    }

    @Test
    public void shouldReRunHandle_eachRebuild() {
        // Proves the rebuild RE-INVOKES the handle (fresh resolution), not a cached value.
        int[] calls = {0};
        PendingProposal p = proposal(() -> {
            calls[0]++;
            return new PreparedMutation<>(new NoOp("op"), "e", "id");
        });
        builder.rebuild(p);
        builder.rebuild(p);
        assertEquals("handle invoked on every rebuild", 2, calls[0]);
    }

    @Test
    public void shouldTranslateUnknownRuntimeFailure_toStaleMutationException() {
        // An UNKNOWN runtime failure (a vanished/incompatible target making the re-invoked prepareXxx
        // blow up) must surface as the generic stale MutationException, NOT a raw RuntimeException or
        // NPE. This is the generic-translation path and it is correct as-is.
        //
        // NOTE ON THE NAME: this test was previously called
        // shouldTranslateModelAccessException_toMutationException, but its body throws a PLAIN
        // RuntimeException — the ModelAccessException case was never exercised anywhere in the tree
        // and merely READ as covered. It is now pinned separately, below.
        PendingProposal p = proposal(() -> {
            throw new RuntimeException("getObjectByID returned null for a deleted element");
        });
        try {
            builder.rebuild(p);
            fail("expected MutationException for an unresolvable target");
        } catch (MutationException e) {
            assertTrue("plain-language stale message",
                    e.getMessage().toLowerCase().contains("can no longer be applied"));
        }
    }

    @Test
    public void shouldSurfaceDomainReason_whenHandleThrowsModelAccessException() {
        // ModelAccessException extends RuntimeException, so the generic catch above used to swallow a
        // genuine, actionable domain reason and replace it with the generic stale sentence — telling the
        // human in the approval dock the WRONG reason and destroying the remedy sentence.
        //
        // The exact text prepareDeleteFolder throws when the human dropped content into a folder that was
        // proposed for a non-force delete. Asserted by EQUALITY, not contains(): a contains() assertion
        // cannot catch a mangled or truncated human sentence.
        String domainSentence = "Folder 'Ops' is not empty: 1 element(s), 0 subfolder(s). "
                + "Use force: true to cascade-delete all contents.";
        PendingProposal p = proposal(() -> {
            throw new ModelAccessException(domainSentence, ErrorCode.FOLDER_NOT_EMPTY);
        });
        try {
            builder.rebuild(p);
            fail("expected MutationException carrying the domain reason");
        } catch (MutationException e) {
            assertEquals("the domain reason reaches the caller verbatim", domainSentence, e.getMessage());
        }
    }

    @Test
    public void shouldPreserveModelAccessExceptionAsCause_forDiagnostics() {
        ModelAccessException domain = new ModelAccessException(
                "Cannot delete default ArchiMate folder: Business", ErrorCode.CANNOT_DELETE_DEFAULT_FOLDER);
        PendingProposal p = proposal(() -> {
            throw domain;
        });
        try {
            builder.rebuild(p);
            fail("expected MutationException");
        } catch (MutationException e) {
            assertSame("the original domain exception is retained as the cause", domain, e.getCause());
        }
    }

    @Test
    public void shouldPreserveMutationException_fromHandle() {
        MutationException original = new MutationException("Element exists but type changed");
        PendingProposal p = proposal(() -> {
            throw original;
        });
        try {
            builder.rebuild(p);
            fail("expected the original MutationException");
        } catch (MutationException e) {
            assertSame("a MutationException from the handle passes through unchanged", original, e);
        }
    }

    @Test
    public void shouldTreatNullCommand_asStale() {
        PendingProposal p = proposal(() -> new PreparedMutation<>(null, "e", "id"));
        try {
            builder.rebuild(p);
            fail("a null rebuilt command must surface as stale, not NPE downstream");
        } catch (MutationException e) {
            assertTrue(e.getMessage().toLowerCase().contains("can no longer be applied"));
        }
    }


    // ---- putIfPresent: the shared guarded-put fold -------------------------

    @Test
    public void shouldWriteNoKey_whenValueIsNull() {
        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putIfPresent(changes, "documentation", null, "folderId", "f-1");

        assertFalse("a null value must produce NO key, not a null-valued one",
                changes.containsKey("documentation"));
        assertEquals("f-1", changes.get("folderId"));
        assertEquals(1, changes.size());
    }

    @Test
    public void shouldWriteKey_whenValueIsBlank() {
        // A blank name is how a name-wipe is disclosed at all: the card's explicitly-blank path
        // depends on the key being PRESENT with an empty value. Folding must not collapse
        // "blank" into "absent".
        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putIfPresent(changes, "name", "");

        assertTrue("an explicitly blank value still writes its key", changes.containsKey("name"));
        assertEquals("", changes.get("name"));
    }

    @Test
    public void shouldPreserveInsertionOrder_acrossOnePutIfPresentCall() {
        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putIfPresent(changes, "a", 1, "skipped", null, "b", 2, "c", 3);

        assertEquals("[a, b, c]", changes.keySet().toString());
    }

    @Test
    public void shouldRejectANonStringKey() {
        // The signature is Object... so nothing stops a future call site from passing a non-String
        // at a key position. This is now the shared fold point for every proposal card, so it must
        // name the mistake rather than surface as a ClassCastException from inside the map.
        Map<String, Object> changes = new LinkedHashMap<>();
        try {
            ProposalBuilder.putIfPresent(changes, 42, "value");
            fail("a non-String key is a programming error and must be named");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("key"));
        }
    }

    @Test
    public void shouldRejectOddArgumentCount() {
        Map<String, Object> changes = new LinkedHashMap<>();
        try {
            ProposalBuilder.putIfPresent(changes, "name", "n", "dangling");
            fail("an odd key/value count is a programming error and must not be silently dropped");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("key/value"));
        }
    }

    // ---- putStyling / putImageParams: the record disclosure ----------------

    @Test
    public void shouldDiscloseRecedeOnlyStyling() {
        // THE TRAP THIS PIN EXISTS FOR. StylingParams.hasAnyValue() checks 16 of its 17 fields --
        // recede is deliberately excluded because it governs the PARENT's fill, not this object's
        // styling. But it still changes what the approval writes (it suppresses the parent's
        // auto-recede), so a disclosure guarded on hasAnyValue() would silently omit a
        // recede-only call -- reproducing the very defect this work closes.
        StylingParams recedeOnly = new StylingParams(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, Boolean.FALSE);
        assertFalse("precondition: hasAnyValue() does NOT see recede", recedeOnly.hasAnyValue());

        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putContainerVisuals(changes, recedeOnly, null);

        assertTrue("a recede-only call must still be disclosed where recede is applied",
                changes.containsKey("styling"));
        assertEquals(Boolean.FALSE, asMap(changes.get("styling")).get("recede"));
    }

    @Test
    public void shouldNotDiscloseRecede_whereItIsNotApplied() {
        // THE OTHER END OF THE SAME RULE. recede is read by exactly one command, wrapped at exactly
        // two prepare sites -- the ones behind add-to-view and add-group-to-view. Everywhere else
        // the write path never reads it, so disclosing it would announce a styling change the
        // approval will not make. Under-disclosing hides a write; over-disclosing invents one.
        StylingParams recedeOnly = new StylingParams(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, Boolean.FALSE);

        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putVisuals(changes, recedeOnly, null);

        assertTrue("a recede-only call must disclose NOTHING where recede is inert, not an empty "
                + "styling object that announces a change and describes none: " + changes,
                changes.isEmpty());
    }

    @Test
    public void shouldStillDiscloseRealStyling_whereRecedeIsNotApplied() {
        // The negative control for the test above: scoping recede must not suppress the fields
        // that DO apply at those sites.
        StylingParams styling = new StylingParams("#FF0000", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, Boolean.FALSE);

        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putVisuals(changes, styling, null);

        Map<String, Object> disclosed = asMap(changes.get("styling"));
        assertEquals("#FF0000", disclosed.get("fillColor"));
        assertFalse("recede is inert here and must not be listed", disclosed.containsKey("recede"));
        assertEquals(1, disclosed.size());
    }

    @Test
    public void shouldWriteNoImageParamsKey_whenEveryFieldIsUnset() {
        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putImageParams(changes, ImageParams.NONE);

        assertTrue("an all-unset record writes nothing, not an empty object", changes.isEmpty());
    }

    @Test
    public void shouldDiscloseOnlySetStylingFields() {
        StylingParams styling = new StylingParams("#FF0000", null, null, 128, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putStyling(changes, styling);

        Map<String, Object> disclosed = asMap(changes.get("styling"));
        assertEquals("#FF0000", disclosed.get("fillColor"));
        assertEquals(128, disclosed.get("opacity"));
        assertEquals("unset fields must not appear as null-valued keys", 2, disclosed.size());
    }

    @Test
    public void shouldWriteNoStylingKey_whenStylingIsNull() {
        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putStyling(changes, null);
        ProposalBuilder.putImageParams(changes, null);

        assertTrue("no styling params means no disclosure at all", changes.isEmpty());
    }

    @Test
    public void shouldDiscloseOnlySetImageParamsFields() {
        Map<String, Object> changes = new LinkedHashMap<>();
        ProposalBuilder.putImageParams(changes, new ImageParams("img/a.png", null, "always"));

        Map<String, Object> disclosed = asMap(changes.get("imageParams"));
        assertEquals("img/a.png", disclosed.get("imagePath"));
        assertEquals("always", disclosed.get("showIcon"));
        assertEquals(2, disclosed.size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertTrue("the record is disclosed as a plain JSON-native map, not the record itself",
                value instanceof Map);
        return (Map<String, Object>) value;
    }

    // ---- helpers ----

    private static PendingProposal proposal(Supplier<PreparedMutation<?>> rebuild) {
        return new PendingProposal("p-1", "update-element", "Update element: id-1",
                rebuild, StalenessCapture.EMPTY, "entityDto",
                null, null, "valid", Instant.now(), null, null);
    }

    private static final class NoOp extends Command {
        NoOp(String label) {
            super(label);
        }
        @Override public void execute() { /* no-op */ }
    }
}
