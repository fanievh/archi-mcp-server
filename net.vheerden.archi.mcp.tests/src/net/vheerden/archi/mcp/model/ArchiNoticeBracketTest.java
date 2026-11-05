package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

/**
 * What Archi's model views are told while an agent's entry runs, through Archi's own editor model manager.
 * A run that completes tells them exactly what Archi's notifying compound tells them. A run that fails ends
 * every notifying compound it started, after the put-back's own model events, so the views do not go on
 * buffering every later change.
 *
 * <p>Requires the OSGi/PDE runtime ({@link IEditorModelManager}'s static instance); skipped elsewhere.</p>
 */
public class ArchiNoticeBracketTest {

    private final List<String> heard = new ArrayList<>();
    private final PropertyChangeListener listener = this::hear;
    private IArchimateModel model;
    private IFolder business;

    @Before
    public void setUp() {
        assumeTrue("needs Archi's editor model manager", managerAvailable());
        model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        business = model.getFolder(FolderType.BUSINESS);
        IEditorModelManager.INSTANCE.registerModel(model);
        IEditorModelManager.INSTANCE.addPropertyChangeListener(listener);
    }

    @After
    public void tearDown() throws Exception {
        if (model != null) {
            IEditorModelManager.INSTANCE.removePropertyChangeListener(listener);
            IEditorModelManager.INSTANCE.closeModel(model, false);
        }
    }

    @Test
    public void shouldTellTheViewsWhatArchiTellsThem_whenNothingThrows() {
        Command archis = tree("a1", "b1", null);
        archis.execute();
        List<String> expected = List.copyOf(heard);
        archis.undo();
        heard.clear();

        MutationDispatcher.executeEntry(new CommandStack(), new AgentAuthoredCompoundCommand(tree("a2", "b2", null)));

        assertEquals(expected.size(), heard.size());
        assertEquals(expected.toString().replace("1", "2"), heard.toString());
    }

    @Test
    public void shouldEndEveryStartedCompound_afterThePutBack_whenACommandThrows() {
        try {
            MutationDispatcher.executeEntry(new CommandStack(), new AgentAuthoredCompoundCommand(tree("a", "b",
                    new IllegalStateException("thrown"))));
        } catch (AgentAuthoredCompoundCommand.Failed expected) {
            // the failure itself is pinned headless
        }

        assertEquals("one end per start: " + heard, count("start"), count("end"));
        assertEquals(List.of("start Outer", "event ADD a", "start Inner", "event ADD b", "event REMOVE b",
                "event REMOVE a", "end Inner", "end Outer"), heard);
        assertTrue("both put back", business.getElements().isEmpty());
    }

    /** {@code Outer[add a, Inner[add b, throw?]]}, both Archi's notifying compounds. */
    private Command tree(String a, String b, RuntimeException thrown) {
        NonNotifyingCompoundCommand inner = new NonNotifyingCompoundCommand("Inner");
        inner.add(add(b));
        if (thrown != null) {
            inner.add(new Command("Throw") {
                @Override
                public void execute() {
                    throw thrown;
                }
            });
        }
        NonNotifyingCompoundCommand outer = new NonNotifyingCompoundCommand("Outer");
        outer.add(add(a));
        outer.add(inner);
        return outer;
    }

    private Command add(String name) {
        IArchimateElement element = IArchimateFactory.eINSTANCE.createBusinessActor();
        element.setName(name);
        return new Command("Add " + name) {
            @Override
            public void execute() {
                business.getElements().add(element);
            }

            @Override
            public void undo() {
                business.getElements().remove(element);
            }
        };
    }

    private void hear(PropertyChangeEvent evt) {
        String property = evt.getPropertyName();
        if (IEditorModelManager.PROPERTY_ECORE_EVENTS_START.equals(property)) {
            heard.add("start " + ((Command) evt.getSource()).getLabel());
        } else if (IEditorModelManager.PROPERTY_ECORE_EVENTS_END.equals(property)) {
            heard.add("end " + ((Command) evt.getSource()).getLabel());
        } else if (IEditorModelManager.PROPERTY_ECORE_EVENT.equals(property)
                && evt.getNewValue() instanceof Notification n && n.getNotifier() == business) {
            Object value = n.getEventType() == Notification.ADD ? n.getNewValue() : n.getOldValue();
            heard.add("event " + (n.getEventType() == Notification.ADD ? "ADD " : "REMOVE ")
                    + ((IArchimateElement) value).getName());
        }
    }

    private long count(String prefix) {
        return heard.stream().filter(h -> h.startsWith(prefix + " ")).count();
    }

    private static boolean managerAvailable() {
        try {
            return IEditorModelManager.INSTANCE != null;
        } catch (LinkageError e) {
            return false;
        }
    }
}
