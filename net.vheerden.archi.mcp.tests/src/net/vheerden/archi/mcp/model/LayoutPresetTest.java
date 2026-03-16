package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Tests for {@link LayoutPreset} (Story 9-1).
 */
public class LayoutPresetTest {

	@Test
	public void resolve_compact_shouldReturnGridWithTightSpacing() {
		LayoutPreset p = LayoutPreset.resolve("compact");
		assertEquals("grid", p.algorithmName());
		assertEquals(20, p.defaultOptions().get("spacing"));
	}

	@Test
	public void resolve_spacious_shouldReturnTreeWithGenerousSpacing() {
		LayoutPreset p = LayoutPreset.resolve("spacious");
		assertEquals("tree", p.algorithmName());
		assertEquals(80, p.defaultOptions().get("spacing"));
	}

	@Test
	public void resolve_hierarchical_shouldReturnTree() {
		LayoutPreset p = LayoutPreset.resolve("hierarchical");
		assertEquals("tree", p.algorithmName());
		assertEquals(50, p.defaultOptions().get("spacing"));
	}

	@Test
	public void resolve_organic_shouldReturnSpring() {
		LayoutPreset p = LayoutPreset.resolve("organic");
		assertEquals("spring", p.algorithmName());
		assertEquals(50, p.defaultOptions().get("spacing"));
	}

	@Test(expected = ModelAccessException.class)
	public void resolve_invalid_shouldThrow() {
		LayoutPreset.resolve("banana");
	}

	@Test
	public void resolve_invalid_shouldListValidPresets() {
		try {
			LayoutPreset.resolve("huge");
			fail("Should have thrown ModelAccessException");
		} catch (ModelAccessException e) {
			assertTrue("Error should mention 'huge'",
					e.getMessage().contains("huge"));
			assertTrue("Error should list compact",
					e.getMessage().contains("compact"));
			assertTrue("Error should list spacious",
					e.getMessage().contains("spacious"));
			assertTrue("Error should list hierarchical",
					e.getMessage().contains("hierarchical"));
			assertTrue("Error should list organic",
					e.getMessage().contains("organic"));
		}
	}

	@Test
	public void listPresets_shouldContainAllPresets() {
		String list = LayoutPreset.listPresets();
		assertNotNull(list);
		assertTrue("Should contain compact", list.contains("compact"));
		assertTrue("Should contain spacious", list.contains("spacious"));
		assertTrue("Should contain hierarchical", list.contains("hierarchical"));
		assertTrue("Should contain organic", list.contains("organic"));
	}
}
