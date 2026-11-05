package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.EMOJI;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.NASTY;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.visible;
import static net.vheerden.archi.mcp.model.ConnectionArgumentEchoTest.whyMalformed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Pins {@link CallerText#bare}: a value repeated without quotes reads exactly as sent while it is
 * ordinary, and as {@link CallerText#quoted} repeats it otherwise, so the two can never disagree on
 * what is ordinary.
 */
public class CallerTextBareTest {

    /** Man, zero-width joiner, woman: one emoji sequence whose joiner is kept. */
    private static final String JOINED = "\uD83D\uDC68\u200D\uD83D\uDC69";

    @Test
    public void shouldRepeatAnOrdinaryValueExactly() {
        assertEquals("ghost-1", CallerText.bare("ghost-1"));
        assertEquals("id-0123456789abcdef0123456789abcdef", CallerText.bare("id-0123456789abcdef0123456789abcdef"));
        assertEquals("a".repeat(40), CallerText.bare("a".repeat(40)));
        assertEquals("", CallerText.bare(""));
        assertEquals("café 中", CallerText.bare("café 中"));
    }

    @Test
    public void shouldRepeatNullAsBefore() {
        assertEquals("null", CallerText.bare(null));
    }

    @Test
    public void shouldKeepJoinersAndAnEmojiPairBare() {
        assertEquals(EMOJI, CallerText.bare(EMOJI));
        assertEquals(JOINED, CallerText.bare(JOINED));
        assertEquals("a\u200Cb", CallerText.bare("a\u200Cb"));
    }

    @Test
    public void shouldQuoteEveryValueThatNeedsEscapingOrCutting() {
        List<String> needs = List.of("a\nb", "a\tb", "a\u0000b", "it's", "a\\b", "x\uD83D", "a\u2028b", "\u200Fx",
                "a".repeat(41), "a".repeat(39) + EMOJI);
        List<String> wrong = new ArrayList<>();
        for (String value : needs) {
            if (!CallerText.quoted(value).equals(CallerText.bare(value))) {
                wrong.add(visible(value) + " -> " + visible(CallerText.bare(value)));
            }
        }
        assertTrue(wrong.toString(), wrong.isEmpty());
        assertEquals("'it\\'s'", CallerText.bare("it's"));
        assertEquals("'" + "a".repeat(39) + "' (the start of 41 characters)", CallerText.bare("a".repeat(39) + EMOJI));
    }

    @Test
    public void everyNastyValue_shouldBeItselfOrQuoted_andWellFormed() {
        List<String> broken = new ArrayList<>();
        for (String value : NASTY) {
            String bare = CallerText.bare(value);
            if (!bare.equals(value) && !bare.equals(CallerText.quoted(value))) {
                broken.add(visible(value) + ": neither itself nor quoted: " + visible(bare));
            }
            String why = whyMalformed(bare);
            if (why != null) {
                broken.add(visible(value) + ": " + why + " in " + visible(bare));
            }
        }
        assertTrue(broken.size() + " broken:\n" + String.join("\n", broken), broken.isEmpty());
    }
}
