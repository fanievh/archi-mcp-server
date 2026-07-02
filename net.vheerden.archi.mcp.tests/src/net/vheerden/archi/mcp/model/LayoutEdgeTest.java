package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for {@link LayoutEdge} — verifies the label-width extension keeps the
 * 3-arg back-compat constructor delegating with a zero reserved width.
 */
public class LayoutEdgeTest {

	@Test
	public void shouldDefaultLabelWidthToZero_when3ArgConstructorUsed() {
		LayoutEdge edge = new LayoutEdge("src", "tgt", "conn-1");

		assertEquals("src", edge.sourceViewObjectId());
		assertEquals("tgt", edge.targetViewObjectId());
		assertEquals("conn-1", edge.connectionId());
		assertEquals(0, edge.labelWidth());
	}

	@Test
	public void shouldPreserveLabelWidth_when4ArgConstructorUsed() {
		LayoutEdge edge = new LayoutEdge("src", "tgt", "conn-1", 162);

		assertEquals("src", edge.sourceViewObjectId());
		assertEquals("tgt", edge.targetViewObjectId());
		assertEquals("conn-1", edge.connectionId());
		assertEquals(162, edge.labelWidth());
	}
}
