package net.vheerden.archi.mcp.ui;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link IpAddressFieldEditor} validation logic.
 *
 * <p>Tests use the static {@link IpAddressFieldEditor#isValidAddress(String)} method
 * which exercises the same validation logic as {@code doCheckState()}.</p>
 */
public class IpAddressFieldEditorTest {

    @Test
    public void shouldAcceptValidIpv4_whenStandardAddress() {
        assertTrue("127.0.0.1 should be valid", IpAddressFieldEditor.isValidAddress("127.0.0.1"));
        assertTrue("0.0.0.0 should be valid", IpAddressFieldEditor.isValidAddress("0.0.0.0"));
        assertTrue("192.168.1.1 should be valid", IpAddressFieldEditor.isValidAddress("192.168.1.1"));
        assertTrue("255.255.255.255 should be valid", IpAddressFieldEditor.isValidAddress("255.255.255.255"));
        assertTrue("10.0.0.1 should be valid", IpAddressFieldEditor.isValidAddress("10.0.0.1"));
    }

    @Test
    public void shouldAcceptLocalhost_whenLocalhostEntered() {
        assertTrue("localhost should be valid", IpAddressFieldEditor.isValidAddress("localhost"));
    }

    @Test
    public void shouldRejectInvalidIp_whenOctetOutOfRange() {
        assertFalse("999.999.999.999 should be invalid", IpAddressFieldEditor.isValidAddress("999.999.999.999"));
        assertFalse("256.0.0.1 should be invalid", IpAddressFieldEditor.isValidAddress("256.0.0.1"));
        assertFalse("0.256.0.0 should be invalid", IpAddressFieldEditor.isValidAddress("0.256.0.0"));
        assertFalse("0.0.256.0 should be invalid", IpAddressFieldEditor.isValidAddress("0.0.256.0"));
        assertFalse("0.0.0.256 should be invalid", IpAddressFieldEditor.isValidAddress("0.0.0.256"));
    }

    @Test
    public void shouldRejectEmptyString_whenNoInput() {
        assertFalse("empty string should be invalid", IpAddressFieldEditor.isValidAddress(""));
    }

    @Test
    public void shouldRejectNull_whenNullInput() {
        assertFalse("null should be invalid", IpAddressFieldEditor.isValidAddress(null));
    }

    @Test
    public void shouldRejectMalformedInput_whenInvalidFormat() {
        assertFalse("abc should be invalid", IpAddressFieldEditor.isValidAddress("abc"));
        assertFalse("1.2.3 should be invalid", IpAddressFieldEditor.isValidAddress("1.2.3"));
        assertFalse("1.2.3.4.5 should be invalid", IpAddressFieldEditor.isValidAddress("1.2.3.4.5"));
        assertFalse("1.2.3. should be invalid", IpAddressFieldEditor.isValidAddress("1.2.3."));
        assertFalse(".1.2.3.4 should be invalid", IpAddressFieldEditor.isValidAddress(".1.2.3.4"));
        assertFalse("spaces should be invalid", IpAddressFieldEditor.isValidAddress("127.0.0.1 "));
    }

    @Test
    public void shouldRejectHostnames_whenNotLocalhost() {
        // Only 'localhost' is accepted, not other hostnames
        assertFalse("example.com should be invalid", IpAddressFieldEditor.isValidAddress("example.com"));
        assertFalse("my-server should be invalid", IpAddressFieldEditor.isValidAddress("my-server"));
        assertFalse("LOCALHOST should be invalid (case-sensitive)", IpAddressFieldEditor.isValidAddress("LOCALHOST"));
    }
}
