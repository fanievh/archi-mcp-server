package net.vheerden.archi.mcp.model;

import java.util.LinkedHashMap;
import java.util.Map;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Semantic layout presets mapping preset names to algorithm + default options.
 * Package-visible — only used by {@link ArchiModelAccessorImpl}.
 */
enum LayoutPreset {

	COMPACT("compact", "grid", Map.of("spacing", 20), "tight grid for maximum information density"),
	SPACIOUS("spacious", "tree", Map.of("spacing", 80), "generous tree for readability and annotation room"),
	HIERARCHICAL("hierarchical", "tree", Map.of("spacing", 50), "top-down tree reflecting relationship direction"),
	ORGANIC("organic", "spring", Map.of("spacing", 50), "force-directed clustering of related elements");

	private static final Map<String, LayoutPreset> BY_NAME = new LinkedHashMap<>();

	static {
		for (LayoutPreset p : values()) {
			BY_NAME.put(p.presetName, p);
		}
	}

	private final String presetName;
	private final String algorithmName;
	private final Map<String, Object> defaultOptions;
	private final String description;

	LayoutPreset(String presetName, String algorithmName, Map<String, Object> defaultOptions, String description) {
		this.presetName = presetName;
		this.algorithmName = algorithmName;
		this.defaultOptions = defaultOptions;
		this.description = description;
	}

	String presetName() {
		return presetName;
	}

	String algorithmName() {
		return algorithmName;
	}

	Map<String, Object> defaultOptions() {
		return defaultOptions;
	}

	/**
	 * Resolves a preset by name. Throws ModelAccessException if invalid.
	 */
	static LayoutPreset resolve(String presetName) {
		LayoutPreset p = BY_NAME.get(presetName);
		if (p == null) {
			throw new ModelAccessException(
					"Invalid preset '" + presetName + "'. Valid presets: " + listPresets(),
					ErrorCode.INVALID_PARAMETER);
		}
		return p;
	}

	/**
	 * Returns a formatted string listing all valid preset names with descriptions.
	 */
	static String listPresets() {
		StringBuilder sb = new StringBuilder();
		BY_NAME.forEach((name, preset) -> {
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(name).append(" (").append(preset.description).append(")");
		});
		return sb.toString();
	}
}
