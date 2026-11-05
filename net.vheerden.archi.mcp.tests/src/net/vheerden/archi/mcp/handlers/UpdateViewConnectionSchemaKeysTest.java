package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ConnectionUpdateArguments;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Pins that the keys {@code update-view-connection} accepts are the keys its served schema declares,
 * plus {@code lineStyle} and nothing else.
 *
 * <p>The tool refuses any argument outside its accepted set, so a property added to the schema
 * without being added to the set would be advertised and then refused, and a key added to the set
 * without the schema would be accepted and never advertised. The schema compared is the one a client
 * is served, after the {@code modelId} decoration every model-addressing tool receives.</p>
 */
public class UpdateViewConnectionSchemaKeysTest {

    @Test
    public void acceptedKeys_shouldEqualTheServedSchemaKeysPlusLineStyle() {
        CommandRegistry registry = new CommandRegistry();
        registry.setToolDecorator(ModelCallScope::withModelId);
        new ViewPlacementHandler(new BaseTestAccessor(true), new ResponseFormatter(), registry, null)
                .registerTools();
        McpSchema.Tool served = registry.getToolSpecifications().stream()
                .map(s -> s.tool())
                .filter(t -> t.name().equals("update-view-connection"))
                .findFirst().orElseThrow();

        Set<String> servedKeys = new TreeSet<>(served.inputSchema().properties().keySet());
        assertTrue("the decoration must have run, or the comparison proves nothing: " + servedKeys,
                servedKeys.contains("modelId"));

        List<String> acceptedLessLineStyle = new ArrayList<>(ConnectionUpdateArguments.ACCEPTED);
        assertTrue(acceptedLessLineStyle.remove("lineStyle"));
        assertEquals("accepted (less lineStyle) = served schema", servedKeys, new TreeSet<>(acceptedLessLineStyle));
        assertEquals("the declared list the refusal names is the served schema", servedKeys,
                new TreeSet<>(ConnectionUpdateArguments.DECLARED));
        assertEquals("and lists each key once", ConnectionUpdateArguments.DECLARED.size(), servedKeys.size());
    }
}
