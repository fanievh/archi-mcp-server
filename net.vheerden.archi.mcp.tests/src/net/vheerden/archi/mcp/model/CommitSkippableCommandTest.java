package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;

/**
 * When a dispatched command, possibly a compound, vouches that it left the model exactly as it was: every
 * part must vouch, and a skippable compound answers for itself rather than through its children.
 */
public class CommitSkippableCommandTest {

    @Test
    public void shouldVouch_whenEveryChildVouches() {
        assertTrue(CommitSkippableCommand.vouchesNothingChanged(compound(new Vouching(), new Vouching())));
    }

    @Test
    public void shouldNotVouch_whenAPlainChildRan() {
        assertFalse(CommitSkippableCommand.vouchesNothingChanged(compound(new Vouching(), new Command("Ran") { })));
    }

    @Test
    public void shouldNotVouch_whenASkippableChildRan() {
        assertFalse(CommitSkippableCommand.vouchesNothingChanged(compound(new Vouching(), new RanSkippable())));
    }

    @Test
    public void shouldNotVouch_forAnEmptyCompound() {
        assertFalse(CommitSkippableCommand.vouchesNothingChanged(new CompoundCommand("Empty")));
    }

    @Test
    public void shouldNotVouch_forAPlainCommand() {
        assertFalse(CommitSkippableCommand.vouchesNothingChanged(new Command("Plain") { }));
    }

    @Test
    public void shouldVouch_whenANestedCompoundOfVouchingChildrenIsTheOnlyChild() {
        assertTrue(CommitSkippableCommand.vouchesNothingChanged(
                compound(compound(new Vouching(), new Vouching()))));
    }

    /** The batch commit's compound is Archi's notifying one; it is a compound like any other here. */
    @Test
    public void shouldVouch_whenEveryChildOfANotifyingCompoundVouches() {
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand("Batch");
        compound.add(new Vouching());
        compound.add(new Vouching());

        assertTrue(CommitSkippableCommand.vouchesNothingChanged(compound));
    }

    @Test
    public void shouldVouch_whenATopLevelSkippableVouches() {
        assertTrue(CommitSkippableCommand.vouchesNothingChanged(new Vouching()));
    }

    /**
     * A skippable compound can write while each of its children, read alone, looks untouched (a folder
     * delete removes the folder itself after its contents). Its own answer is the one that counts.
     */
    @Test
    public void shouldNotVouch_whenASkippableCompoundSaysItWrote_thoughEachOfItsChildrenWouldVouch() {
        SkippableCompound wrote = new SkippableCompound(false);
        wrote.add(new Vouching());
        wrote.add(new Vouching());

        assertFalse(CommitSkippableCommand.vouchesNothingChanged(wrote));
        assertFalse(CommitSkippableCommand.vouchesNothingChanged(compound(wrote)));
    }

    @Test
    public void shouldVouch_whenASkippableCompoundVouches_thoughItsChildRan() {
        SkippableCompound declined = new SkippableCompound(true);
        declined.add(new Command("Never ran") { });

        assertTrue(CommitSkippableCommand.vouchesNothingChanged(compound(declined, new Vouching())));
    }

    private static CompoundCommand compound(Command... children) {
        CompoundCommand compound = new CompoundCommand("Compound");
        for (Command child : children) {
            compound.add(child);
        }
        return compound;
    }

    /** Declined and vouches that it wrote nothing. */
    private static final class Vouching extends Command implements CommitSkippableCommand {
        @Override
        public String getSkipReason() {
            return "declined";
        }

        @Override
        public boolean changedNothing() {
            return true;
        }
    }

    /** Ran: no reason, no vouch. */
    private static final class RanSkippable extends Command implements CommitSkippableCommand {
        @Override
        public String getSkipReason() {
            return null;
        }
    }

    /** A skippable that is also a compound, answering for itself whatever its children say. */
    private static final class SkippableCompound extends CompoundCommand implements CommitSkippableCommand {
        private final boolean changedNothing;

        SkippableCompound(boolean changedNothing) {
            this.changedNothing = changedNothing;
        }

        @Override
        public String getSkipReason() {
            return "declined after writing";
        }

        @Override
        public boolean changedNothing() {
            return changedNothing;
        }
    }
}
