package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;

/**
 * The accessor's convenience overloads that the interface answers itself: each forwards to the
 * overload an implementor writes, with the value the shorter call leaves out, and never to another
 * default, so an implementor that writes only the full overload is never sent round in a loop.
 */
public class ArchiModelAccessorDefaultsTest {

    @Test
    public void shouldCreateAnElementWithNoSource_whenTheShorterOverloadIsCalled() {
        List<String> heard = new ArrayList<>();
        ArchiModelAccessor accessor = new BaseTestAccessor() {
            @Override
            public MutationResult<ElementDto> createElement(String sessionId, String type, String name,
                    String documentation, Map<String, String> properties, String folderId,
                    Map<String, String> source, String specialization) {
                heard.add(String.join("|", sessionId, type, name, documentation, String.valueOf(properties),
                        folderId, String.valueOf(source), specialization));
                return null;
            }
        };

        accessor.createElement("s", "BusinessActor", "Customer", "docs", Map.of("k", "v"), "folder-1", "VIP");

        assertEquals(List.of("s|BusinessActor|Customer|docs|{k=v}|folder-1|null|VIP"), heard);
    }

    @Test
    public void shouldResizeWithoutWrapFit_whenTheShorterOverloadIsCalled() {
        List<String> heard = new ArrayList<>();
        ArchiModelAccessor accessor = new BaseTestAccessor() {
            @Override
            public MutationResult<ResizeElementsResultDto> resizeElementsToFit(String sessionId, String viewId,
                    List<String> elementIds, boolean wrapFit) {
                heard.add(sessionId + "|" + viewId + "|" + elementIds + "|" + wrapFit);
                return null;
            }
        };

        accessor.resizeElementsToFit("s", "view-1", List.of("box-1"));

        assertEquals(List.of("s|view-1|[box-1]|false"), heard);
    }
}
