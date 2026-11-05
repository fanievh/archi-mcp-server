package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One model open in Archi, as {@code get-models} lists it.
 *
 * @param id            the model id, which a call passes as {@code modelId}
 * @param name          the model name
 * @param file          the file it was opened from or saved to; absent for a model never saved
 * @param dirty         whether it holds unsaved changes
 * @param sessionActive whether a call from the calling session that names no model resolves to it — with
 *                      two or more models open and no batch or default, only a read does, since a change
 *                      that names no model is refused
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenModelDto(
    String id,
    String name,
    String file,
    boolean dirty,
    boolean sessionActive
) {
}
