package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.IOException;

import org.junit.Test;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * The constructor that carries the structured client fields must also carry a cause.
 *
 * <p>Without it, a failure that had an exception in hand and wanted to give the caller details and
 * a correction had to choose, and chose the client fields: the exception was dropped and the Archi
 * log, the only place a server fault's cause can be read, got the message alone.</p>
 */
public class ModelAccessExceptionTest {

    @Test
    public void shouldCarryCauseAndEveryClientField_whenBuiltWithBoth() {
        IOException cause = new IOException("disk full");

        ModelAccessException e = new ModelAccessException("Failed to add image to model: disk full",
                cause, ErrorCode.INTERNAL_ERROR, "disk full", "Free some space", "ArchiMate 3.2");

        assertSame(cause, e.getCause());
        assertEquals("Failed to add image to model: disk full", e.getMessage());
        assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
        assertEquals("disk full", e.getDetails());
        assertEquals("Free some space", e.getSuggestedCorrection());
        assertEquals("ArchiMate 3.2", e.getArchiMateReference());
    }
}
