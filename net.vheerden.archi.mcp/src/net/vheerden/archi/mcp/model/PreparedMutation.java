package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

/**
 * Encapsulates the result of preparing a mutation before dispatch (Story 7-5).
 *
 * <p>Package-visible — only used within the {@code model/} package by
 * {@link ArchiModelAccessorImpl}. Separates mutation object creation and
 * command building (Phase 1) from dispatch (Phase 2), enabling bulk
 * operations to pre-validate all mutations before executing any.</p>
 *
 * @param <T> the DTO type (e.g., ElementDto, RelationshipDto, ViewDto)
 * @param command   the GEF command ready for CommandStack execution
 * @param entity    the DTO representation of the created/updated object
 * @param entityId  the unique identifier of the entity
 * @param rawObject the raw EMF object (for back-reference wiring in bulk ops), may be null
 */
record PreparedMutation<T>(Command command, T entity, String entityId, Object rawObject) {

    /**
     * Convenience constructor without raw object (used by single-mutation paths).
     */
    PreparedMutation(Command command, T entity, String entityId) {
        this(command, entity, entityId, null);
    }
}
