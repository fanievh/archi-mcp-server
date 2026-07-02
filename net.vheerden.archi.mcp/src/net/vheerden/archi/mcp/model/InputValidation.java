package net.vheerden.archi.mcp.model;

import java.util.regex.Pattern;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Input validation helpers for user-supplied names, labels, and label-expression
 * templates on create/add/update mutation operations.
 *
 * <p>This class lives OUTSIDE {@link ArchiModelAccessorImpl} on purpose: it is a
 * small, unmeasured collaborator so the facade's size ratchet
 * ({@code tools/size-ratchet.sh}) is unaffected. The facade delegates by wrapping
 * the offending argument in place — {@code setName(reject(name, "name"))} — so no
 * measured line is added.</p>
 *
 * <p><b>Reject, do not unescape.</b> When a caller supplies a literal HTML/XML
 * entity (e.g. {@code &amp;} from JSON-escaping muscle memory) in a name or label,
 * the model would silently store the five-character literal where a human expects a
 * single {@code &}. Auto-unescaping is lossy and ambiguous (a genuinely-intended
 * {@code &amp;amp;} would be corrupted), so this validator REJECTS the value with a
 * hint instead, keeping the plugin a faithful verbatim store.</p>
 */
final class InputValidation {

    private InputValidation() {}

    /**
     * Well-formed HTML/XML entity grammar: the five named entities plus decimal and
     * hexadecimal numeric character references, each requiring a trailing {@code ;}.
     *
     * <p>The required trailing semicolon is what lets a bare ampersand through:
     * {@code "R&D"} / {@code "A & B"} contain no complete entity token and are
     * ACCEPTED; {@code "R&amp;D"} contains the {@code &amp;} token and is REJECTED.
     * {@code "&amp;amp;"} (double-escape) begins with a complete {@code &amp;} token
     * and is also REJECTED. Angle brackets on their own ({@code "<tag>"}) are not
     * entities and are ACCEPTED.</p>
     */
    private static final Pattern ENTITY =
            Pattern.compile("&(amp|lt|gt|quot|apos|#[0-9]+|#[xX][0-9a-fA-F]+);");

    /**
     * Pass-through validator: returns {@code value} unchanged when it is clean, and
     * throws {@link ModelAccessException} when it contains a literal HTML/XML entity.
     *
     * <p>Designed for in-place argument wrapping: {@code element.setName(reject(name,
     * "name"))}. {@code null} (the "leave unchanged" / not-supplied sentinel on update
     * paths) is a no-op and returns {@code null}. Blank/empty is NOT this method's
     * concern — the existing per-field blank guards remain the source of truth for
     * that — so an empty {@code labelExpression} (which means "clear") passes through.</p>
     *
     * @param value     the raw user-supplied string, or {@code null}
     * @param fieldName the field label used in the rejection message (e.g. {@code "name"})
     * @return {@code value} unchanged when clean
     * @throws ModelAccessException with {@link ErrorCode#INVALID_PARAMETER} when a
     *         literal entity token is present anywhere in {@code value}
     */
    static String reject(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (ENTITY.matcher(value).find()) {
            throw new ModelAccessException(
                    "The " + fieldName + " contains a literal HTML/XML entity "
                            + "(e.g. \"&amp;\", \"&lt;\", \"&#160;\"). The model stores text "
                            + "verbatim, so this would persist the escaped literal instead of "
                            + "the character it represents.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use the actual character instead of the entity (e.g. \"&\" not \"&amp;\", "
                            + "\"<\" not \"&lt;\"). This store does not support literal entity text.",
                    null);
        }
        return value;
    }
}
