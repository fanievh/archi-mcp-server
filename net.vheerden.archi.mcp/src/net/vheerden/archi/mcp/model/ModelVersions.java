package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateModel;

/**
 * One change counter per open model instance, so that a change to one model never moves the version
 * an agent reads for another, in either direction.
 *
 * <p>Weak keys, so a closed model is not held. Guarded by the map itself.</p>
 */
final class ModelVersions {

    private final Map<IArchimateModel, AtomicLong> versions = new WeakHashMap<>();

    /** Advances {@code model}'s version; does nothing for null. */
    void bump(IArchimateModel model) {
        if (model != null) {
            counter(model).incrementAndGet();
        }
    }

    /** {@code model}'s current version. */
    long get(IArchimateModel model) {
        return counter(model).get();
    }

    /**
     * Advances the version of the model a model-content event belongs to, and of no other. The event
     * carries an EMF notification whose notifier sits inside one model's containment; an event that
     * cannot be traced to a model advances every model's version, because a missed change would serve
     * a stale answer while an extra one only costs a cache miss.
     */
    void bumpOwnerOf(Object event) {
        IArchimateModel owner = event instanceof Notification notification
                ? ownerOf(notification.getNotifier()) : null;
        if (owner != null) {
            bump(owner);
            return;
        }
        List<AtomicLong> all;
        synchronized (versions) {
            all = new ArrayList<>(versions.values());
        }
        all.forEach(AtomicLong::incrementAndGet);
    }

    private AtomicLong counter(IArchimateModel model) {
        synchronized (versions) {
            return versions.computeIfAbsent(model, m -> new AtomicLong());
        }
    }

    private static IArchimateModel ownerOf(Object notifier) {
        Object node = notifier;
        while (node instanceof EObject object) {
            if (object instanceof IArchimateModel model) {
                return model;
            }
            node = object.eContainer();
        }
        return null;
    }
}
