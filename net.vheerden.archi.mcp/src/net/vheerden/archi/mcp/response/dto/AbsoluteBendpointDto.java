package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for an absolute-coordinate bendpoint.
 *
 * <p>Represents a single routing point on a visual connection using
 * absolute canvas coordinates.</p>
 *
 * <p><strong>On a response this value is derived, not read.</strong> Archi stores a bendpoint
 * twice — once as an offset from the source centre, once from the target centre — and a response
 * reconstructs both and interpolates between them at the weight Archi draws with: bendpoint
 * {@code i} of {@code n} sits {@code (i + 1) / (n + 1)} of the way from the source-anchored
 * reconstruction to the target-anchored one. Element centres are whole pixels, matching the
 * reference points the renderer's anchors use, and the interpolation is exact.</p>
 *
 * <p>The two reconstructions describe the same point only while both endpoints sit where they sat
 * when the route was written. While they agree the weight makes no difference and the reported
 * point is exact. Once an endpoint moves or is resized they diverge, and the drawn polyline is
 * sheared: the reported point follows that shear, displaced from the halfway point by
 * {@code (weight - 0.5) x drift} — furthest at the first and last bendpoints, least in the middle.
 * That divergence is what the layout assessor reports as anchor drift, and while it is non-zero no
 * single coordinate is a value the model holds on either axis.</p>
 *
 * <p>On a request the direction reverses and nothing is lost: the server converts an absolute
 * point into both offsets, which by construction agree.</p>
 */
public record AbsoluteBendpointDto(int x, int y) {}
