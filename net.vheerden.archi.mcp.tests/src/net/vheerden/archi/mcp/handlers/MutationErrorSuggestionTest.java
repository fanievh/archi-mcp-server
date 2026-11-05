package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * A mutation failure tells the caller what to do next: the failure's own advice when it has some, such as
 * a failure that may have left part of a change in the model, and otherwise the generic advice to retry.
 */
public class MutationErrorSuggestionTest {

    @Test
    public void shouldSuggestARetry_whenTheFailureHasNoAdviceOfItsOwn() throws Exception {
        Map<?, ?> error = error(new MutationException("No model loaded"));

        assertEquals("Mutation failed: No model loaded", error.get("message"));
        assertEquals("Retry the operation or check the model state", error.get("suggestedCorrection"));
    }

    @Test
    public void shouldGiveTheFailuresOwnAdvice_whenItHasSome() throws Exception {
        Map<?, ?> error = error(new MutationException("Partly applied") {
            private static final long serialVersionUID = 1L;

            @Override
            public String getSuggestedCorrection() {
                return "Check the model.";
            }
        });

        assertEquals("Mutation failed: Partly applied", error.get("message"));
        assertEquals("Check the model.", error.get("suggestedCorrection"));
    }

    private static Map<?, ?> error(MutationException e) throws Exception {
        McpSchema.CallToolResult result = HandlerUtils.buildMutationError(new ResponseFormatter(), e);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        Map<String, Object> parsed = new ObjectMapper().readValue(text, new TypeReference<Map<String, Object>>() { });
        return (Map<?, ?>) parsed.get("error");
    }
}
