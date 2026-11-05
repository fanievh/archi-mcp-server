package net.vheerden.archi.mcp.model;

import java.util.Map;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;

import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;

/**
 * What a focused mode of {@code auto-route-connections} ({@code terminals-only}, {@code labels-only}) has prepared,
 * handed to the one tail that stores it as a proposal or dispatches or queues it.
 *
 * @param compound        the commands to apply; may be empty
 * @param dto             the response
 * @param label           the undo label, also the batch entry's description
 * @param proposedChanges the approval card
 * @param summary         the proposal's opening sentence
 */
record AutoRoutePlan(NonNotifyingCompoundCommand compound, AutoRouteResultDto dto, String label,
        Map<String, Object> proposedChanges, String summary) {
}
