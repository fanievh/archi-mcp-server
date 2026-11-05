package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.draw2d.text.FlowPage;
import org.eclipse.draw2d.text.ParagraphTextLayout;
import org.eclipse.draw2d.text.TextFlow;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.ViewNoteDto;

/**
 * Checks a note's fitted height against an independent measure of the text it holds: draw2d's own
 * flow layout ({@link FlowPage} and {@link TextFlow}, the wrapping a note figure draws with) at the
 * note's content width. The fit must leave room for the laid-out text plus its padding, and no more
 * than one extra line above that.
 *
 * <p>The oracle lays the text out in the font the estimator measures with, so this checks how hard
 * line breaks are counted, not which font a note is drawn in. It never consults the assessor's clip
 * check, which shares the estimator and so cannot tell the two apart. It needs a display.</p>
 */
public class NoteFitRenderedHeightOracleTest {

    private static final int WIDTH = 440;
    private static final int CONTENT_WIDTH = WIDTH - ElementSizer.HORIZONTAL_TEXT_INSET;
    private static final int FLOOR = 80;

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-note-fit-oracle");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private static String lines(String line, int times) {
        return String.join("\n", java.util.Collections.nCopies(times, line));
    }

    /** The height draw2d lays {@code text} out at, at the note's content width, in the estimator's font. */
    private static int laidOut(String text) {
        AtomicInteger height = new AtomicInteger();
        Display.getDefault().syncExec(() -> {
            FlowPage page = new FlowPage();
            TextFlow flow = new TextFlow(text);
            flow.setLayoutManager(new ParagraphTextLayout(flow, ParagraphTextLayout.WORD_WRAP_SOFT));
            page.add(flow);
            page.setFont(Display.getDefault().getSystemFont());
            height.set(page.getPreferredSize(CONTENT_WIDTH, -1).height);
        });
        return height.get();
    }

    private static int lineHeight() {
        return ElementSizer.measureText("Ag").lineHeight();
    }

    @Test
    public void shouldFitEachNoteWithinOneLineOfItsLaidOutText() {
        List<String> texts = List.of(
                lines("Line one", 5),
                lines("Short line of thirty chars ok.", 6),
                lines("This line is about sixty characters long, give or take a bit.", 6),
                ("The quick brown fox jumps over the lazy dog while the architect reviews the model. ").repeat(4)
                        .trim(),
                "• Live component\n→ Data flow\n● Deployed\n◐ Partly deployed\n○ Planned");
        int line = lineHeight();
        List<String> wrong = new ArrayList<>();
        int y = 0;
        for (String text : texts) {
            ViewNoteDto note = f.accessor.addNoteToView(ViewContentsFixture.SESSION, f.view.getId(), text, null,
                    null, 10, y, WIDTH, null, null, null, null).entity();
            y += 700;
            int textHeight = laidOut(text);
            int low = Math.max(FLOOR, textHeight + ElementSizer.LABEL_VERTICAL_PADDING);
            int high = Math.max(FLOOR, textHeight + ElementSizer.LABEL_VERTICAL_PADDING + line);
            if (note.height() < low || note.height() > high) {
                wrong.add(text.replace("\n", "\\n").substring(0, Math.min(40, text.length())) + "… fitted "
                        + note.height() + ", laid out " + textHeight + " → expected " + low + ".." + high);
            }
        }
        assertTrue(String.join("\n", wrong), wrong.isEmpty());
    }

    @Test
    public void shouldFitTwoNotesOfSixHardLinesWithinOneLineOfEachOther_whateverTheirLineLength() {
        int sixty = f.accessor.addNoteToView(ViewContentsFixture.SESSION, f.view.getId(),
                lines("This line is about sixty characters long, give or take a bit.", 6), null, null, 10, 10, WIDTH,
                null, null, null, null).entity().height();
        int thirty = f.accessor.addNoteToView(ViewContentsFixture.SESSION, f.view.getId(),
                lines("Short line of thirty chars ok.", 6), null, null, 10, 400, WIDTH, null, null, null, null)
                .entity().height();

        assertTrue("sixty-char lines " + sixty + ", thirty-char lines " + thirty,
                Math.abs(sixty - thirty) < lineHeight());
    }
}
