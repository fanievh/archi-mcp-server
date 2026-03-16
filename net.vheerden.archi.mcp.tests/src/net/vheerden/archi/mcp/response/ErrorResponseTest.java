package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link ErrorResponse} and {@link ErrorCode}.
 */
public class ErrorResponseTest {

    @Test
    public void shouldCreateWithAllFields() {
        ErrorResponse error = new ErrorResponse(
                ErrorCode.ELEMENT_NOT_FOUND,
                "No element found",
                "Model has 100 elements",
                "Use search-elements",
                "https://pubs.opengroup.org/architecture/archimate3-doc/");

        assertEquals(ErrorCode.ELEMENT_NOT_FOUND, error.getCode());
        assertEquals("No element found", error.getMessage());
        assertEquals("Model has 100 elements", error.getDetails());
        assertEquals("Use search-elements", error.getSuggestedCorrection());
        assertEquals("https://pubs.opengroup.org/architecture/archimate3-doc/",
                error.getArchiMateReference());
    }

    @Test
    public void shouldCreateWithCodeAndMessageOnly() {
        ErrorResponse error = new ErrorResponse(ErrorCode.MODEL_NOT_LOADED, "No model");

        assertEquals(ErrorCode.MODEL_NOT_LOADED, error.getCode());
        assertEquals("No model", error.getMessage());
        assertNull(error.getDetails());
        assertNull(error.getSuggestedCorrection());
        assertNull(error.getArchiMateReference());
    }

    @Test
    public void shouldSerializeToMap_withAllFields() {
        ErrorResponse error = new ErrorResponse(
                ErrorCode.ELEMENT_NOT_FOUND,
                "Not found",
                "Details here",
                "Try search",
                "ref-url");

        Map<String, Object> map = error.toMap();

        assertEquals("ELEMENT_NOT_FOUND", map.get("code"));
        assertEquals("Not found", map.get("message"));
        assertEquals("Details here", map.get("details"));
        assertEquals("Try search", map.get("suggestedCorrection"));
        assertEquals("ref-url", map.get("archiMateReference"));
        assertEquals(5, map.size());
    }

    @Test
    public void shouldOmitNullFieldsFromMap() {
        ErrorResponse error = new ErrorResponse(ErrorCode.INTERNAL_ERROR, "Server error");

        Map<String, Object> map = error.toMap();

        assertEquals("INTERNAL_ERROR", map.get("code"));
        assertEquals("Server error", map.get("message"));
        assertFalse(map.containsKey("details"));
        assertFalse(map.containsKey("suggestedCorrection"));
        assertFalse(map.containsKey("archiMateReference"));
        assertEquals(2, map.size());
    }

    @Test
    public void shouldHaveAllExpectedErrorCodes() {
        ErrorCode[] codes = ErrorCode.values();
        assertEquals(35, codes.length);
        assertNotNull(ErrorCode.valueOf("ELEMENT_NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("MODEL_NOT_LOADED"));
        assertNotNull(ErrorCode.valueOf("INVALID_PARAMETER"));
        assertNotNull(ErrorCode.valueOf("INVALID_CURSOR"));
        assertNotNull(ErrorCode.valueOf("VIEW_NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("FOLDER_NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("INTERNAL_ERROR"));
        assertNotNull(ErrorCode.valueOf("BATCH_ALREADY_ACTIVE"));
        assertNotNull(ErrorCode.valueOf("BATCH_NOT_ACTIVE"));
        assertNotNull(ErrorCode.valueOf("MUTATION_FAILED"));
        assertNotNull(ErrorCode.valueOf("INVALID_ELEMENT_TYPE"));
        assertNotNull(ErrorCode.valueOf("INVALID_RELATIONSHIP_TYPE"));
        assertNotNull(ErrorCode.valueOf("RELATIONSHIP_NOT_ALLOWED"));
        assertNotNull(ErrorCode.valueOf("SOURCE_ELEMENT_NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("TARGET_ELEMENT_NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("POTENTIAL_DUPLICATES"));
        assertNotNull(ErrorCode.valueOf("BULK_VALIDATION_FAILED"));
        assertNotNull(ErrorCode.valueOf("APPROVAL_NOT_ACTIVE"));
        assertNotNull(ErrorCode.valueOf("PROPOSAL_NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("PROPOSAL_STALE"));
        assertNotNull(ErrorCode.valueOf("ELEMENT_ALREADY_ON_VIEW"));
        assertNotNull(ErrorCode.valueOf("RELATIONSHIP_NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("VIEW_OBJECT_NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("CONNECTION_ALREADY_ON_VIEW"));
        assertNotNull(ErrorCode.valueOf("RELATIONSHIP_MISMATCH"));
        assertNotNull(ErrorCode.valueOf("FORMAT_NOT_AVAILABLE"));
        // Story 8-5: Folder mutation + move error codes
        assertNotNull(ErrorCode.valueOf("FOLDER_NOT_EMPTY"));
        assertNotNull(ErrorCode.valueOf("CANNOT_DELETE_DEFAULT_FOLDER"));
        assertNotNull(ErrorCode.valueOf("CIRCULAR_FOLDER_REFERENCE"));
        assertNotNull(ErrorCode.valueOf("CANNOT_MOVE_DEFAULT_FOLDER"));
        assertNotNull(ErrorCode.valueOf("CANNOT_CREATE_ROOT_FOLDER"));
        assertNotNull(ErrorCode.valueOf("ALREADY_IN_TARGET_FOLDER"));
        assertNotNull(ErrorCode.valueOf("OBJECT_NOT_FOUND"));
        assertNotNull(ErrorCode.valueOf("INVALID_MOVE_TARGET"));
        // Story 10-13: Folder layer validation
        assertNotNull(ErrorCode.valueOf("FOLDER_LAYER_MISMATCH"));
    }
}
