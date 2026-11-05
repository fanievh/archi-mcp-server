package net.vheerden.archi.mcp.model;

import com.archimatetool.model.IDiagramModelComponent;

/**
 * A command that takes one object off a view. A batch refuses to queue a second command that takes off
 * an object a queued command already takes off: the second would find it gone at commit, or would put it
 * back a second time on Undo.
 */
interface RemovesViewObject {

    /** The view object, or connection, this command takes off its view. */
    IDiagramModelComponent removedViewObject();
}
