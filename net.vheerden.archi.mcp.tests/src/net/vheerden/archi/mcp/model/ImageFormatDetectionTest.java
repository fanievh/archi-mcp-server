package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.vheerden.archi.mcp.response.ErrorCode;

import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the three pure string functions behind {@code formatDetected}:
 * {@link ImageOperations#detectFormat(String)}, {@link ImageOperations#extensionFromFileName(String)}
 * and {@link ImageOperations#extensionFromUrlPath(String)}.
 *
 * <p>Pure string-mapping test — no EMF, SWT or OSGi required. Run as standard JUnit test.</p>
 *
 * <p><b>Why this exists.</b> The format label is derived from the file extension by a switch whose
 * {@code default} arm is {@code "PNG"}. Any extension the switch does not name is therefore
 * reported as PNG rather than as unknown — so a missing arm is invisible unless something asserts
 * the mapping directly. That is exactly how {@code .svg} came to be reported as {@code PNG} while
 * importing and rendering correctly: measured on Archi 5.10, 2026-08-14.</p>
 *
 * <p><b>Why the whole class runs under a Turkish default locale.</b> These functions lower-case, and
 * lower-casing is locale-sensitive: {@code "TIFF".toLowerCase()} yields a dotless ı under Turkish and
 * so misses its own switch arm. Four of the eight format tokens are affected — TIFF, TIF, ICO and
 * GIF, the ones containing an {@code I} — while SVG, PNG, JPEG and BMP are not and cannot
 * discriminate the defect at all. Pinning the locale for every arm means a regression to a
 * locale-sensitive {@code toLowerCase()} anywhere in this trio turns a test red rather than passing
 * on the developer's machine and silently mislabelling a user's image.</p>
 *
 * <p>The default is restored unconditionally in {@link #restoreLocale()}: the harness runs test
 * classes in one JVM, so a leaked default locale would poison every class that runs after this one.</p>
 */
public class ImageFormatDetectionTest {

    private Locale originalLocale;

    @Before
    public void forceTurkishLocale() {
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    // ---- extensions the switch names explicitly ----

    @Test
    public void shouldDetectPng_whenExtensionIsPng() {
        assertEquals("PNG", ImageOperations.detectFormat("icon.png"));
    }

    @Test
    public void shouldDetectJpeg_whenExtensionIsEitherJpgSpelling() {
        assertEquals("JPEG", ImageOperations.detectFormat("photo.jpg"));
        assertEquals("JPEG", ImageOperations.detectFormat("photo.jpeg"));
    }

    @Test
    public void shouldDetectGif_whenExtensionIsGif() {
        assertEquals("GIF", ImageOperations.detectFormat("anim.gif"));
    }

    @Test
    public void shouldDetectBmp_whenExtensionIsBmp() {
        assertEquals("BMP", ImageOperations.detectFormat("raster.bmp"));
    }

    @Test
    public void shouldDetectIco_whenExtensionIsIco() {
        assertEquals("ICO", ImageOperations.detectFormat("favicon.ico"));
    }

    @Test
    public void shouldDetectTiff_whenExtensionIsEitherTiffSpelling() {
        assertEquals("TIFF", ImageOperations.detectFormat("scan.tiff"));
        assertEquals("TIFF", ImageOperations.detectFormat("scan.tif"));
    }

    // ---- the arm that was missing ----

    @Test
    public void shouldDetectSvg_whenExtensionIsSvg() {
        assertEquals("SVG", ImageOperations.detectFormat("aws-lambda.svg"));
    }

    @Test
    public void shouldDetectSvg_whenExtensionIsUppercase() {
        assertEquals("SVG", ImageOperations.detectFormat("AWS-LAMBDA.SVG"));
    }

    // ---- fallback behaviour, pinned so the default arm is deliberate rather than accidental ----

    @Test
    public void shouldFallBackToPng_whenExtensionIsUnrecognised() {
        assertEquals("PNG", ImageOperations.detectFormat("mystery.xyz"));
    }

    @Test
    public void shouldFallBackToPng_whenNameHasNoExtension() {
        assertEquals("PNG", ImageOperations.detectFormat("no-extension-here"));
    }

    @Test
    public void shouldReadTheLastExtension_whenNameHasSeveralDots() {
        assertEquals("SVG", ImageOperations.detectFormat("aws.arch.icons.v2.svg"));
    }

    // ---- the locale arms: the four tokens a Turkish toLowerCase() actually mangles ----
    //
    // Under the tr locale forced above, an uppercase I lower-cases to a dotless ı. A
    // locale-sensitive toLowerCase() would turn ".TIFF" into "tıff", which matches no switch arm
    // and falls through to the "PNG" default — a TIFF silently reported as a PNG. SVG/PNG/JPEG/BMP
    // contain no I, so an arm built on them is green either way and proves nothing.

    @Test
    public void shouldDetectTiff_whenExtensionIsUppercaseUnderATurkishLocale() {
        assertEquals("TIFF", ImageOperations.detectFormat("scan.TIFF"));
        assertEquals("TIFF", ImageOperations.detectFormat("scan.TIF"));
    }

    @Test
    public void shouldDetectIco_whenExtensionIsUppercaseUnderATurkishLocale() {
        assertEquals("ICO", ImageOperations.detectFormat("favicon.ICO"));
    }

    @Test
    public void shouldDetectGif_whenExtensionIsUppercaseUnderATurkishLocale() {
        assertEquals("GIF", ImageOperations.detectFormat("anim.GIF"));
    }

    // ---- extensionFromFileName: the derivation behind the imageData source's temp file ----

    @Test
    public void shouldLowerCaseFileNameExtension_independentlyOfTheDefaultLocale() {
        assertEquals("tiff", ImageOperations.extensionFromFileName("logo.TIFF"));
        assertEquals("tif", ImageOperations.extensionFromFileName("logo.TIF"));
        assertEquals("ico", ImageOperations.extensionFromFileName("logo.ICO"));
        assertEquals("gif", ImageOperations.extensionFromFileName("logo.GIF"));
    }

    @Test
    public void shouldDefaultFileNameExtensionToPng_whenThereIsNoDot() {
        assertEquals("png", ImageOperations.extensionFromFileName("no-extension-here"));
    }

    @Test
    public void shouldDefaultFileNameExtensionToPng_whenNameIsNull() {
        assertEquals("png", ImageOperations.extensionFromFileName(null));
    }

    @Test
    public void shouldReadTheLastFileNameExtension_whenNameHasSeveralDots() {
        assertEquals("svg", ImageOperations.extensionFromFileName("aws.arch.icons.v2.svg"));
    }

    /**
     * The end-to-end consequence the locale fix exists to prevent, asserted as one chain: the
     * derived extension names the temp file, and {@code detectFormat} reads that same name back.
     * With a locale-sensitive lower-case at either end this reports "PNG" for a TIFF.
     */
    @Test
    public void shouldRoundTripAnUppercaseHintToItsOwnFormat_underATurkishLocale() {
        String tempFileName = "archi-mcp-image-1234." + ImageOperations.extensionFromFileName("logo.TIFF");
        assertEquals("TIFF", ImageOperations.detectFormat(tempFileName));
    }

    // ---- extensionFromUrlPath: same derivation plus the weird-URL fallbacks, moved verbatim ----

    @Test
    public void shouldLowerCaseUrlExtension_independentlyOfTheDefaultLocale() {
        assertEquals("tiff", ImageOperations.extensionFromUrlPath("/assets/logo.TIFF"));
        assertEquals("ico", ImageOperations.extensionFromUrlPath("/favicon.ICO"));
    }

    @Test
    public void shouldDefaultUrlExtensionToPng_whenPathIsNullOrHasNoDot() {
        assertEquals("png", ImageOperations.extensionFromUrlPath(null));
        assertEquals("png", ImageOperations.extensionFromUrlPath("/assets/logo"));
    }

    /**
     * A last-dot split on a URL path is not a file-name split: the last dot can sit in a directory
     * segment, leaving a path separator in the "extension". Pinned because this fallback moved out of
     * {@code addImageFromUrl} verbatim and nothing else covers it.
     */
    @Test
    public void shouldFallBackToPng_whenTheLastDotSitsInADirectorySegment() {
        assertEquals("png", ImageOperations.extensionFromUrlPath("/a.b/c"));
    }

    /**
     * The length guard, pinned at its exact boundary: 5 characters is kept and 6 falls back, so a
     * mutation from {@code > 5} to {@code >= 5} (or to {@code > 6}) turns this red.
     */
    @Test
    public void shouldFallBackToPng_onlyOnceTheExtensionExceedsFiveCharacters() {
        assertEquals("aaaaa", ImageOperations.extensionFromUrlPath("/img.aaaaa"));
        assertEquals("png", ImageOperations.extensionFromUrlPath("/img.aaaaaa"));
    }

    @Test
    public void shouldKeepAnOrdinaryUrlExtension() {
        assertEquals("png", ImageOperations.extensionFromUrlPath("/icons/aws-eks.png"));
        assertEquals("svg", ImageOperations.extensionFromUrlPath("/icons/aws-eks.svg"));
    }

    // ---- the remediation register: one sentence, four call sites ----

    /**
     * The four image remediations in this package share one constant so they cannot drift apart —
     * which is the defect this fixes: the SVG condition previously reached two of three import
     * sources and the fourth remediation still enumerated the formats without it.
     *
     * <p>Reachable headlessly because the empty-data guard fires before anything touches the model,
     * so a supplier returning {@code null} is enough. This asserts the constant is actually
     * <i>referenced</i> at the format-list site rather than merely defined.</p>
     */
    @Test
    public void shouldCarryTheSvgConditionOnTheEmptyDataRemediation() {
        ImageOperations ops = new ImageOperations(() -> null);
        try {
            ops.addImageToModel("session", new byte[0], "icon.png");
            org.junit.Assert.fail("empty image data must be rejected");
        } catch (ModelAccessException e) {
            String remediation = e.getSuggestedCorrection();
            assertTrue("must still enumerate the formats it accepts",
                    remediation.contains("PNG, JPEG, GIF, BMP, ICO, TIFF"));
            assertTrue("must carry the shared SVG condition", remediation.contains("SVG"));
            assertTrue("must name the boundary", remediation.contains("from 5.10 onward"));
            assertTrue("must state the portability consequence",
                    remediation.contains("re-saved on an older Archi"));
        }
    }

    /**
     * The shared sentence must state a general rule, never a diagnosis of the call in hand.
     *
     * <p>It is appended to four errors. Three fire when an image could not be loaded — for any
     * reason, so naming SVG as the cause would be a guess — and the fourth fires when the caller sent
     * <b>no bytes at all</b>, where any sentence about what failed to load is nonsense. An earlier
     * draft opened "If the image was an SVG:", which reads as a diagnosis and is incoherent at the
     * empty-payload site; this pins the rule form instead.</p>
     */
    @Test
    public void shouldStateTheSvgConditionAsAGeneralRule_notAsADiagnosisOfThisCall() {
        String note = ImageOperations.SVG_HOST_SUPPORT_NOTE;
        assertTrue("must state the condition as a standing rule",
                note.contains("accepted only where the host supports it"));
        assertFalse("must not diagnose the call in hand", note.contains("this host"));
        assertFalse("must not presume the caller sent an SVG", note.contains("was an SVG"));
    }

    /**
     * The same three facts are stated on two surfaces — this one and {@code ImageHandler}'s schema
     * descriptions — by two constants, because {@code handlers/} cannot see a package-private type in
     * {@code model/}. No single test can see both constants, so each surface is instead pinned
     * against the <b>same fact list</b>. {@code ImageHandlerTest} asserts the other half.
     */
    @Test
    public void shouldStateAllThreeSvgFacts_inTheRemediationSurface() {
        String note = ImageOperations.SVG_HOST_SUPPORT_NOTE;
        assertTrue("fact 1 — the capability is host-supplied", note.contains("only where the host supports it"));
        assertTrue("fact 2 — the boundary", note.contains("from 5.10 onward"));
        assertTrue("fact 3 — the portability consequence", note.contains("re-saved on an older Archi"));
    }

    // ---- boundaries preserved verbatim from the code these helpers were extracted from ----

    /**
     * A trailing dot is NOT the "no extension" case: the split finds a dot and returns what follows,
     * which is the empty string. Pinned because the two look alike and only one gets the {@code "png"}
     * default — the distinction is exactly what a reader of the Javadoc could otherwise get wrong.
     * Verified identical to the pre-extraction code.
     */
    @Test
    public void shouldReturnAnEmptyExtension_whenTheNameEndsInADot() {
        assertEquals("", ImageOperations.extensionFromFileName("logo."));
        assertEquals("", ImageOperations.extensionFromFileName("."));
        assertEquals("", ImageOperations.extensionFromUrlPath("/icon."));
    }

    /**
     * {@code extensionFromFileName} still derives whatever the last-dot split produces — that is a
     * pure string function and it is unchanged. What changed is that a derived suffix which cannot
     * name a file is now refused before it reaches {@code File.createTempFile}, instead of throwing
     * there and being reported as a server-internal failure for input the caller typed.
     *
     * <p>This method previously asserted the absence of that guard and named it in its own title,
     * so it was rewritten rather than deleted: it exists to stop a future reader "fixing" the
     * asymmetry by accident, and it now records what the asymmetry actually is. The two helpers
     * still differ, deliberately — the URL sibling falls back to {@code png} because its own
     * contract says an unusable tail is benign there, while a caller-supplied {@code filename} is a
     * direct hint and is refused so the caller learns what they typed wrong.</p>
     */
    @Test
    public void shouldRefuseAPathSeparator_derivedFromAFileNameHint() {
        assertEquals("the split itself is unchanged",
                "2/logo", ImageOperations.extensionFromFileName("v1.2/logo"));

        ModelAccessException refusal = refusalFor("v1.2/logo");
        assertEquals("a caller-input fault is not an internal error",
                ErrorCode.INVALID_PARAMETER, refusal.getErrorCode());
        assertTrue("the message must name the offending character, not just report a failure: "
                + refusal.getMessage(), refusal.getMessage().contains("/"));
        assertNotNull("this project's error contract requires an actionable correction; the "
                + "pre-fix path returned null here", refusal.getSuggestedCorrection());
        assertFalse("an empty correction is the same silence in a different shape",
                refusal.getSuggestedCorrection().isBlank());
    }

    /**
     * The NUL case, which BOTH helpers missed. It is fatal to {@code File.createTempFile} exactly as
     * a separator is, and it is reachable on the URL path too: a percent-encoded {@code %00}
     * survives {@code URI.create} and {@code getPath()} decodes it.
     */
    @Test
    public void shouldRefuseANulByte_derivedFromAFileNameHint() {
        ModelAccessException refusal = refusalFor("logo.pn\u0000g");
        assertEquals(ErrorCode.INVALID_PARAMETER, refusal.getErrorCode());
        assertNotNull(refusal.getSuggestedCorrection());
    }

    /**
     * And the URL sibling stops passing a NUL through — as a fallback, not a refusal, because its
     * published contract already defines an unusable tail as falling back to {@code png} on the
     * grounds that the content sniff decides the real format regardless.
     */
    @Test
    public void shouldFallBackToPng_whenAUrlPathYieldsANulByte() {
        assertEquals("png", ImageOperations.extensionFromUrlPath("/a.p\u0000g"));
        assertEquals("the separator fallback is unchanged",
                "png", ImageOperations.extensionFromUrlPath("/v1.2/logo"));
    }

    /**
     * The two negative controls, without which the guard would be free to refuse anything that
     * merely looks suspicious. Both were measured on this project's target platforms rather than
     * assumed: a backslash is a legal filename character on macOS and Linux, and an over-long
     * suffix is silently truncated by the JDK rather than rejected. There is no Windows lane in
     * this project's CI, so refusing either would refuse input that demonstrably works.
     */
    @Test
    public void shouldNotRefuseABackslashOrALongExtension_whichBothWorkOnTheTargetPlatforms() {
        assertEquals("a backslash is a legal filename character here",
                null, refusalOrNull("logo.pn\\g"));
        assertEquals("the JDK truncates a long suffix rather than failing on it",
                null, refusalOrNull("logo." + "x".repeat(300)));
        assertEquals("a trailing dot yields an empty extension, which is a legal suffix",
                null, refusalOrNull("logo."));
        assertEquals("and the ordinary case is untouched", null, refusalOrNull("logo.png"));
    }

    /** Asserts a refusal happened and hands it back. */
    private static ModelAccessException refusalFor(String filenameHint) {
        ModelAccessException refusal = refusalOrNull(filenameHint);
        if (refusal == null) {
            fail("expected a refusal for filename hint: " + filenameHint);
        }
        return refusal;
    }

    /** The refusal for this hint, or null when the hint is accepted. */
    private static ModelAccessException refusalOrNull(String filenameHint) {
        try {
            ImageOperations.requireUsableExtension(filenameHint,
                    ImageOperations.extensionFromFileName(filenameHint));
            return null;
        } catch (ModelAccessException e) {
            return e;
        }
    }
}
