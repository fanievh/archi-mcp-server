package net.vheerden.archi.mcp.model;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.ui.FontFactory;

/**
 * The font Archi draws a view object or connection in when it carries no font of its own: the user's
 * default view font (Preferences ▸ Appearance ▸ Fonts), on macOS Lucida Grande 12 unless changed.
 *
 * <p>A font the server writes from nothing has to name a family and a height, because the stored form
 * has a cell for each. Writing a fixed height, or an empty family, would draw the label in another size
 * or face than the one it had before a caller changed only its style. So the cells the caller does not
 * set are filled from this font.</p>
 *
 * <p>Read on the workbench's UI thread, since Archi keeps the font in its JFace font registry. Without a
 * running workbench (a test JVM) no display is created: the answer is {@link #UNKNOWN}, an empty family
 * and Archi's own fallback height of 9. When the workbench runs but Archi's font cannot be read, the
 * display's system font is used, then {@link #UNKNOWN}.</p>
 */
public final class DefaultViewFont {

    private static final Logger logger = LoggerFactory.getLogger(DefaultViewFont.class);

    /** A family name and a whole point height. An empty name leaves the family to the platform. */
    public record Spec(String name, int height) {
    }

    /** The answer when Archi's default view font cannot be read. */
    public static final Spec UNKNOWN = new Spec("", 9);

    private DefaultViewFont() {
    }

    /** Archi's default view font now, or {@link #UNKNOWN} when it cannot be read. Never throws. */
    public static Spec read() {
        try {
            if (!PlatformUI.isWorkbenchRunning()) {
                return UNKNOWN;
            }
            Display display = PlatformUI.getWorkbench().getDisplay();
            if (display == null || display.isDisposed()) {
                return UNKNOWN;
            }
            AtomicReference<FontData> data = new AtomicReference<>();
            display.syncExec(() -> data.set(readOnUiThread(display)));
            FontData fd = data.get();
            if (!usable(fd)) {
                return UNKNOWN;
            }
            return new Spec(fd.getName(), fd.getHeight());
        } catch (Throwable t) {
            logger.debug("Archi's default view font could not be read; the platform's is assumed", t);
            return UNKNOWN;
        }
    }

    /** Archi's default view font, or the display's system font when Archi's cannot be read. */
    private static FontData readOnUiThread(Display display) {
        try {
            FontData archi = FontFactory.getDefaultUserViewFontData();
            if (usable(archi)) {
                return archi;
            }
        } catch (RuntimeException | LinkageError e) {
            logger.debug("Archi's default view font could not be read; the system font is used", e);
        }
        FontData[] system = display.getSystemFont() == null ? null : display.getSystemFont().getFontData();
        return system == null || system.length == 0 ? null : system[0];
    }

    private static boolean usable(FontData fd) {
        return fd != null && fd.getName() != null && !fd.getName().isEmpty() && fd.getHeight() > 0;
    }
}
