package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;
import org.junit.Test;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Pure grammar matrix for {@link InputValidation#reject(String, String)} — no Archi
 * model required. Proves the entity-detection grammar in isolation from the wiring
 * (the accessor-level wire tests live in {@code ArchiModelAccessorImplTest}).
 */
public class InputValidationTest {

    // ---- REJECT: well-formed entity tokens anywhere in the string ----

    @Test
    public void rejectsNamedAmp() {
        assertRejected("&amp;");
    }

    @Test
    public void rejectsDoubleEscapedAmp() {
        // The classic footgun: an agent JSON-escapes twice → "&amp;amp;". Begins with a
        // complete &amp; token, so it rejects.
        assertRejected("&amp;amp;");
    }

    @Test
    public void rejectsLt() {
        assertRejected("&lt;");
    }

    @Test
    public void rejectsGt() {
        assertRejected("&gt;");
    }

    @Test
    public void rejectsQuot() {
        assertRejected("&quot;");
    }

    @Test
    public void rejectsApos() {
        assertRejected("&apos;");
    }

    @Test
    public void rejectsNumericDecimal() {
        assertRejected("&#160;");
    }

    @Test
    public void rejectsNumericHexLower() {
        assertRejected("&#xa0;");
    }

    @Test
    public void rejectsNumericHexUpperX() {
        assertRejected("&#XA0;");
    }

    @Test
    public void rejectsTokenMidString() {
        assertRejected("Order &amp; Shipping");
    }

    @Test
    public void rejectionCarriesInvalidParameterAndHint() {
        try {
            InputValidation.reject("R &amp; D", "name");
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertNotNull("must give a corrective hint", e.getSuggestedCorrection());
            assertTrue("message should name the field",
                    e.getMessage().contains("name"));
        }
    }

    // ---- ACCEPT: bare ampersands, non-entity text, and templates (returned unchanged) ----

    @Test
    public void acceptsBareAmpersandSpaced() {
        assertPassThrough("A & B");
    }

    @Test
    public void acceptsBareAmpersandTight() {
        assertPassThrough("A&B");
    }

    @Test
    public void acceptsTomAndJerry() {
        assertPassThrough("Tom&Jerry");
    }

    @Test
    public void acceptsRAndD() {
        assertPassThrough("R&D");
    }

    @Test
    public void acceptsAngleBrackets() {
        // Angle brackets alone are not an entity.
        assertPassThrough("<tag>");
    }

    @Test
    public void acceptsPercentAndGreaterThanText() {
        assertPassThrough("50% > 40%");
    }

    @Test
    public void acceptsAccentedText() {
        assertPassThrough("café");
    }

    @Test
    public void acceptsLabelExpressionTemplateWithBareAmp() {
        // A legitimate label-expression: bare & between two placeholders, no entity token.
        assertPassThrough("${name} & ${property:evidenceMark}");
    }

    @Test
    public void acceptsAmpWithoutTrailingSemicolon() {
        // "&amp" (no ;) is not a complete entity token per the grammar → accepted.
        assertPassThrough("&amp");
    }

    @Test
    public void acceptsBrokenEntity() {
        assertPassThrough("& amp;");
    }

    @Test
    public void acceptsEmptyString() {
        // Blank is not this validator's concern (existing blank guards own that);
        // an empty labelExpression means "clear" and must pass through.
        assertPassThrough("");
    }

    @Test
    public void acceptsNullAsNoOp() {
        assertNull(InputValidation.reject(null, "name"));
    }

    // ---- helpers ----

    private static void assertPassThrough(String value) {
        assertSame("clean value must be returned unchanged (same reference)",
                value, InputValidation.reject(value, "name"));
    }

    private static void assertRejected(String value) {
        try {
            InputValidation.reject(value, "name");
            fail("expected ModelAccessException for: " + value);
        } catch (ModelAccessException expected) {
            assertEquals(ErrorCode.INVALID_PARAMETER, expected.getErrorCode());
        }
    }
}
