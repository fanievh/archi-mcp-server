package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArrangeGroupsResultDto(
    String viewId,
    int groupsPositioned,
    int layoutWidth,
    int layoutHeight,
    Integer columnsUsed,
    String arrangement
) {}
