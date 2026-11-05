package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the sentences that tell a caller what {@code get-view-contents} returns for the objects it used to
 * leave out: the view references, the connections that are not ArchiMate ones or are drawn from another
 * connection, and what the tree, graph and summary formats show of them.
 *
 * <p>Each is checked where a caller reads it (the served tool description, the served view-patterns page,
 * the README), so deleting one, or moving it off the surface it qualifies, turns a pin red. The behaviour
 * each sentence describes is pinned by {@link GetViewContentsViewReferencesTest} and
 * {@link GetViewContentsFormatsTest} and {@link GetViewContentsConnectionsTest}.</p>
 */
public class GetViewContentsDisclosureTest {

    private ViewContentsFixture f;
    private String description;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-view-contents-disclosure");
        description = f.tool("get-view-contents").description();
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- view references ---------------------------------------------------------------------------

    @Test
    public void description_shouldSayWhatAReferenceRowHolds_andThatItsTargetIsTheStoredOne() {
        assertContains(description, "The viewReferences array lists every view reference the view holds, at any "
                + "depth, as the same row add-view-reference-to-view answers with");
        assertContains(description, "That id is what the model stores and can name a view no longer in the "
                + "model; it is omitted when the reference stores none.");
        assertContains(description, "styling (its verticalTextAlignment is Archi's text position), the icon a "
                + "user gave it (imagePath, imagePosition and showIcon, each omitted at Archi's default), its "
                + "labelExpression when it has one, and referencedViewId");
    }

    @Test
    public void viewPatterns_shouldSayWhereAReferenceIsReadBack_andThatItsTargetIsTheStoredOne() {
        String page = read("net.vheerden.archi.mcp/resources/reference/archimate-view-patterns.md");

        assertContains(page, "`get-view-contents` reads every view reference back in its `viewReferences` "
                + "array, at any depth, as the same row `add-view-reference-to-view` answers with");
        assertContains(page, "it can name a view that is no longer in the model");
        assertContains(page, "the icon a user gave it (`imagePath`, `imagePosition` and `showIcon`, each omitted "
                + "at Archi's default; `add-view-reference-to-view` takes them when it places the reference, and "
                + "`update-view-object` sets them)");
        assertContains(page, "elements, relationships, visualMetadata, connections, groups, notes, images, "
                + "viewReferences, nonArchimateConnections");
    }

    @Test
    public void readme_shouldNameTheReferenceArray() {
        assertContains(read("README.md"), "view references (`viewReferences`: each reference's id, bounds, "
                + "parent, styling and the view it points at)");
    }

    // ---- the formats -------------------------------------------------------------------------------

    @Test
    public void description_shouldSayTheTreeShowsImagesAndReferences_andWhatTheLayoutToolMoves() {
        assertContains(description, "A container's children in the tree include the images and view references "
                + "it holds (type 'image', and type 'viewReference' with referencedViewId), which "
                + "layout-within-group positions along with the elements; notes are listed too, and that tool "
                + "leaves them where they are.");
    }

    @Test
    public void description_shouldSayTheGraphAndSummaryShowThem() {
        assertContains(description, "The graph format also carries a node for each note, image and view reference "
                + "(_nodeType 'note', 'image', 'viewReference'), and format=summary counts all three under "
                + "'Visual annotations'.");
    }

    @Test
    public void viewPatterns_shouldSayWhatEachFormatShows() {
        String page = read("net.vheerden.archi.mcp/resources/reference/archimate-view-patterns.md");

        assertContains(page, "A container's children include its notes, images (`type: \"image\"`) and view "
                + "references (`type: \"viewReference\"`, with `referencedViewId`); `layout-within-group` "
                + "positions the images and references with the elements and leaves the notes in place |");
        assertContains(page, "groups, notes, images and view references are nodes tagged `_nodeType` `group` / "
                + "`note` / `image` / `viewReference`");
        assertContains(page, "notes, images and view references are counted under `Visual annotations`");
    }

    @Test
    public void dryRun_shouldSayTheEstimateIsForTheFormatAskedFor() {
        String dryRun = String.valueOf(((java.util.Map<?, ?>) f.tool("get-view-contents").inputSchema().properties()
                .get("dryRun")).get("description"));
        assertContains(dryRun, "The estimate is for the format asked for: json and summary are charged the json "
                + "estimate, and graph and tree add what their responses carry beyond the json one.");
        assertContains(dryRun, "A tree carries every array whatever exclude names, so its estimate does too.");
    }

    @Test
    public void description_shouldSayTheTreeStatsCountEveryKindOfNode() {
        assertContains(description, "The tree stats count every kind of node the tree carries: totalImages and "
                + "totalViewReferences sit beside totalNotes, at every depth, and read 0 when there are none.");
    }

    @Test
    public void readme_shouldSayWhatEachFormatShows() {
        assertContains(read("README.md"), "A container's `children` in `tree` include the images "
                + "(`type: \"image\"`) and view references (`type: \"viewReference\"`, with `referencedViewId`) "
                + "it holds, which `layout-within-group` positions along with the elements (it leaves notes in "
                + "place); `graph` carries them as `_nodeType: \"image\"` / `\"viewReference\"` nodes, and "
                + "`summary` counts them beside the notes under `Visual annotations`.");
    }

    // ---- connections that are not ArchiMate ones, and connections drawn from a connection -----------

    @Test
    public void description_shouldSayWhatAPlainConnectionRowHolds_andThatAnEndCanBeAConnection() {
        assertContains(description, "The nonArchimateConnections array lists every connection that is not an "
                + "ArchiMate one (no relationship stands behind it: one drawn to or from a note, group, image or "
                + "view reference, or with Archi's plain Connection tool, including an ArchiMate connection whose "
                + "relationship is missing, which only a damaged model holds), each with its viewConnectionId, "
                + "sourceViewObjectId and targetViewObjectId, name, documentation, bendpoints, absoluteBendpoints, "
                + "styling, labelExpression, relativePosition, lineStyle, sourceArrowhead/targetArrowhead, "
                + "textAlignment and properties; it has no anchors or render faces, and it is read-only: no tool "
                + "writes a plain connection's styling.");
        assertContains(description, "Either end can be another connection, which Archi allows: an end id then "
                + "names that connection, absoluteBendpoints is omitted, and an ArchiMate connection drawn that "
                + "way is listed in connections with no anchors or render faces at either end.");
        assertContains(description, "A connection that is not an ArchiMate one is a format=graph edge tagged "
                + "_edgeType: 'connection' carrying its viewConnectionId, sourceViewObjectId and "
                + "targetViewObjectId, and format=summary counts those apart from the connections.");
    }

    @Test
    public void removeFromView_shouldNameThePlainConnectionArray() {
        assertContains(f.tool("remove-from-view").inputSchema().properties().get("viewObjectId").toString(),
                "a viewConnectionId from connections or nonArchimateConnections");
    }

    @Test
    public void viewPatterns_shouldSayWhatAPlainConnectionRowHolds() {
        String page = read("net.vheerden.archi.mcp/resources/reference/archimate-view-patterns.md");

        assertContains(page, "`get-view-contents` lists each in its `nonArchimateConnections` array");
        assertContains(page, "`labelExpression`, `relativePosition` (the label offset), `lineStyle` (`dashed` / "
                + "`dotted`), `sourceArrowhead` / `targetArrowhead` (`fill` / `hollow` / `line`), `textAlignment` "
                + "and `properties`. It carries no anchors or render faces, and no tool writes its styling. An "
                + "ArchiMate connection whose relationship is missing, which only a damaged model holds, is listed "
                + "here too. Archi lets either end of a connection be another connection: that end's id is then "
                + "the connection's, and an ArchiMate connection drawn that way is listed in `connections` with no "
                + "anchors or render faces at either end.");
        assertContains(page, "a connection that is not an ArchiMate one is an edge tagged `_edgeType: "
                + "\"connection\"`");
        assertContains(page, "connections that are not ArchiMate ones under `Other connections`");
    }

    @Test
    public void readme_shouldNameThePlainConnectionArray_andWhatTheFormatsShow() {
        String readme = read("README.md");

        assertContains(readme, "connections that are not ArchiMate ones (`nonArchimateConnections`: drawn to or "
                + "from a note, group, image or view reference, or with Archi's plain Connection tool — ids, "
                + "label, bendpoints, styling, line style and arrowheads; no anchors or render faces)");
        assertContains(readme, "A connection that is not an ArchiMate one is a `graph` edge tagged `_edgeType: "
                + "\"connection\"` with its visual ids, and `summary` counts those under `Other connections`.");
    }

    // ---- the bound between assess-layout's coordinates and these -------------------------------------

    @Test
    public void assessLayout_shouldPublishTheBoundTheFrameTestDerives() {
        // AbsoluteBendpointFrameTest derives and reaches this bound; the description and README state it.
        assertContains(f.tool("assess-layout").description(), "That difference is bounded by 1.5 px");
        assertContains(f.tool("assess-layout").description(), "It is exactly 1 px for a lone bendpoint "
                + "and 0.5 + n/(n+1) px whenever n+1 is a power of two (1.25 at three, 1.375 at seven), and "
                + "reaches 1.5 px at every other count.");
        assertContains(read("README.md"), "by up to 1.5 px — exactly 1.0 at one bendpoint, `0.5 + n/(n + 1)` "
                + "when n + 1 is a power of two (1.25 at three, 1.375 at seven), and 1.5 at every other count");
    }

    // ---- the release notes -------------------------------------------------------------------------

    @Test
    public void changelog_shouldNameEachFix_inTheV1_10_0Entry() {
        // The fixes shipped in v1.10.0; its entry is found by version, whether still a draft or dated.
        String changelog = read("CHANGELOG.md");
        int entry = changelog.indexOf("\n## v1.10.0");
        assertTrue("the changelog has a v1.10.0 entry", entry >= 0);
        int next = changelog.indexOf("\n## ", entry + 1);
        String section = changelog.substring(entry, next < 0 ? changelog.length() : next);

        assertContains(section, "**`get-view-contents` now lists the view references a view holds.**");
        assertContains(section, "That id is what the model stores and can name a view that is no longer in the model.");
        assertContains(section, "**The `tree`, `graph` and `summary` formats of `get-view-contents` now show images "
                + "and view references.**");
        assertContains(section, "**`get-view-contents` now lists every connection a view holds.**");
        assertContains(section, "A view with neither kind reads exactly as before.");
    }

    // ---- harness -----------------------------------------------------------------------------------

    private static void assertContains(String surface, String sentence) {
        assertTrue("The published sentence has left this surface (or been reworded without its pin): \""
                + sentence + "\"", surface.contains(sentence));
    }

    /** A file at the repository root, from either working directory the harness runs tests in. */
    private static String read(String repoPath) {
        for (Path path : new Path[] {Paths.get(repoPath), Paths.get("..", repoPath)}) {
            if (Files.isRegularFile(path)) {
                try {
                    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new AssertionError(repoPath + " resolved from neither the repository root nor a bundle "
                + "(cwd " + Paths.get("").toAbsolutePath() + ")");
    }
}
