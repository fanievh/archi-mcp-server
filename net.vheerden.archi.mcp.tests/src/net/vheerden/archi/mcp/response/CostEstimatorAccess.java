package net.vheerden.archi.mcp.response;

/**
 * Reads the estimator's package-private format widths for pins that live beside the served handler in
 * another package, so those pins compare the served shape with the shipped figure rather than with a
 * copy of it.
 */
public final class CostEstimatorAccess {

    private CostEstimatorAccess() {
    }

    public static int graphEnvelopeExcess() {
        return CostEstimator.GRAPH_ENVELOPE_EXCESS_CHARS;
    }

    public static int treeEnvelopeExcess() {
        return CostEstimator.TREE_ENVELOPE_EXCESS_CHARS;
    }

    public static int graphGroupMarker() {
        return CostEstimator.GRAPH_GROUP_MARKER_CHARS;
    }

    public static int graphNoteMarker() {
        return CostEstimator.GRAPH_NOTE_MARKER_CHARS;
    }

    public static int graphImageMarker() {
        return CostEstimator.GRAPH_IMAGE_MARKER_CHARS;
    }

    public static int graphReferenceMarker() {
        return CostEstimator.GRAPH_VIEW_REFERENCE_MARKER_CHARS;
    }
}
