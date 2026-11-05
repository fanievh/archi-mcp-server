package net.vheerden.archi.mcp.response.dto;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The container a placement receded, and the fill it now holds.
 *
 * <p>Nesting a child into an element or group whose fill was never set gives that container a light
 * neutral backdrop, so the parent and its children stay distinguishable. The caller never named that
 * container's fill, so the response reports it: an agent that cannot see the canvas would otherwise
 * keep planning against a container whose colour changed for a reason it was never told.</p>
 *
 * <p>The backdrop is chosen when the placement runs, from the fills its parent and its children hold
 * at that moment. The value is therefore filled in by the command that applies it: when the call
 * runs immediately, the response carries what was written. A placement queued in a batch or awaiting
 * approval has not run, and an earlier operation may yet colour the container or recede it first, so
 * it reports nothing rather than a forecast. A placement that receded nothing reports nothing too,
 * and the field is omitted.</p>
 */
@JsonPropertyOrder({"viewObjectId", "fillColor"})
public final class RecededParentDto {

    private volatile String viewObjectId;
    private volatile String fillColor;

    /** Records that {@code viewObjectId} now holds {@code fillColor}; a null fill records no recede. */
    public void set(String viewObjectId, String fillColor) {
        this.viewObjectId = fillColor != null ? viewObjectId : null;
        this.fillColor = fillColor;
    }

    /** View-object id of the receded container, or null when nothing receded. */
    public String getViewObjectId() {
        return viewObjectId;
    }

    /** The fill the container now holds, or null when nothing receded. */
    public String getFillColor() {
        return fillColor;
    }

    /** Whether a recede is recorded. */
    public boolean receded() {
        return fillColor != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RecededParentDto o && Objects.equals(viewObjectId, o.viewObjectId)
                && Objects.equals(fillColor, o.fillColor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(viewObjectId, fillColor);
    }

    @Override
    public String toString() {
        return receded() ? viewObjectId + " -> " + fillColor : "none";
    }

    /**
     * The JSON inclusion filter for a field of this type: a null value, or one recording no recede,
     * is left out of the response.
     */
    public static final class NoneRecorded {
        @Override
        public boolean equals(Object value) {
            return !(value instanceof RecededParentDto dto) || !dto.receded();
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }
}
