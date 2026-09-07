package net.vheerden.archi.mcp.model.routing;

/**
 * What a routing pass may do to a connection label it cannot place.
 *
 * <p>The routing pass evaluates all three candidate label positions (source, middle, target) for
 * every labeled connection. Some labels collide at every one of them — a dense flow view can leave
 * a handful of labels with nowhere legible to sit. Until now the pass computed that fact and threw
 * it away, so the only remedy was for the caller to hide each label by hand, one call at a time,
 * after inferring which ones from a truncated advisory list.</p>
 *
 * <p><strong>This is opt-in and off by default, deliberately.</strong> The standing design position
 * for connection labels is to <em>prevent crowding</em> rather than to <em>suppress by default</em>:
 * a hidden label is lost information, and hiding one that a human could still read is a worse
 * outcome than a slightly crowded view. So {@link #KEEP} is what an omitted parameter means, and it
 * leaves label visibility byte-identical to a pass that never knew about this option.</p>
 */
public enum LabelPolicy {

    /**
     * Never change label visibility. <strong>The default.</strong> A pass running under this policy
     * touches no label's visibility, whether or not the optimizer found it unplaceable.
     */
    KEEP("keep"),

    /**
     * Hide exactly those labels the optimizer proved have no collision-free position at any of the
     * three candidate positions. Labels with a usable position are left alone, and a label the
     * caller had already hidden by hand is not claimed as a policy hide.
     */
    AUTO_HIDE_ON_COLLISION("auto-hide-on-collision");

    private final String wireValue;

    LabelPolicy(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The value as it appears in the tool parameter. */
    public String wireValue() {
        return wireValue;
    }

    /** True when this policy is allowed to hide a label. */
    public boolean hidesUnplaceableLabels() {
        return this == AUTO_HIDE_ON_COLLISION;
    }

    /**
     * Resolves a wire value to a policy. A null or blank value resolves to {@link #KEEP} — the
     * default-off contract. An unrecognised value is rejected rather than silently treated as the
     * default, so a caller that misspells the opt-in is told, instead of quietly getting no policy.
     *
     * @return the resolved policy, or {@code null} when the value is not recognised
     */
    public static LabelPolicy parse(String value) {
        if (value == null || value.isBlank()) {
            return KEEP;
        }
        String normalized = value.trim();
        for (LabelPolicy policy : values()) {
            if (policy.wireValue.equalsIgnoreCase(normalized)) {
                return policy;
            }
        }
        return null;
    }

    /** Comma-separated list of accepted wire values, for error messages and tool descriptions. */
    public static String allowedValues() {
        StringBuilder sb = new StringBuilder();
        for (LabelPolicy policy : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(policy.wireValue);
        }
        return sb.toString();
    }
}
