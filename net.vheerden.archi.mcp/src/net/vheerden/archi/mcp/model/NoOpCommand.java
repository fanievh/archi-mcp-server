package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

/**
 * A no-op GEF command that performs no action (backlog-b11).
 *
 * <p>Used when a mutation is deduplicated — the entity already exists in the model,
 * so no command stack operation is needed. This allows the PreparedMutation to carry
 * a valid command reference without side effects.</p>
 */
public class NoOpCommand extends Command {

    public NoOpCommand() {
        setLabel("No-op (duplicate detected)");
    }

    @Override
    public void execute() {
        // Intentionally empty — entity already exists
    }

    @Override
    public void undo() {
        // Intentionally empty — nothing was done
    }

    @Override
    public void redo() {
        // Intentionally empty — nothing was done
    }
}
