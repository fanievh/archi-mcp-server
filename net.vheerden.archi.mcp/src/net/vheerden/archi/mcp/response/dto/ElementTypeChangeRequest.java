package net.vheerden.archi.mcp.response.dto;

/**
 * One entry of a {@code change-element-type} call, as the caller sent it: the element to change
 * and the ArchiMate element type it should have.
 *
 * <p>Either component may be null when the caller left it out; the accessor refuses that as a
 * caller error rather than the handler guessing a default.</p>
 */
public record ElementTypeChangeRequest(String elementId, String type) {
}
