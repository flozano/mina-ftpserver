package org.apache.ftpserver.listener.nio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.ftpserver.impl.ProxyProtocolResult;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.session.DummySession;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.filter.FilterEvent;
import org.junit.Before;
import org.junit.Test;

/**
 * Comprehensive unit tests for {@link ProxyProtocolFilter}.
 *
 * <p>Tests cover valid v2 headers (IPv4, IPv6), non-PROXY traffic passthrough,
 * fragmented delivery, LOCAL command, invalid version, already-processed
 * sessions, and edge cases (empty buffer, single-byte fragments).</p>
 */
public class ProxyProtocolFilterTest {

    /** PROXY Protocol v2 12-byte signature. */
    private static final byte[] V2_SIGNATURE = {
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A,
            0x51, 0x55, 0x49, 0x54, 0x0A
    };

    private ProxyProtocolFilter filter;
    private DummySession session;
    private CapturingNextFilter nextFilter;

    @Before
    public void setUp() {
        filter = new ProxyProtocolFilter();
        session = new DummySession();
        nextFilter = new CapturingNextFilter();
    }

    // -----------------------------------------------------------------------
    // 1. Valid v2 IPv4 header
    // -----------------------------------------------------------------------

    @Test
    public void testValidV2IPv4Header() throws Exception {
        // Source: 192.168.1.100:12345, Destination: 10.0.0.1:21
        byte[] srcIp = {(byte) 192, (byte) 168, 1, 100};
        byte[] dstIp = {10, 0, 0, 1};
        int srcPort = 12345;
        int dstPort = 21;

        IoBuffer buf = buildV2Header(0x01, 0x11, srcIp, dstIp, srcPort, dstPort);
        filter.messageReceived(nextFilter, session, buf);

        // Verify session attributes
        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull("ATTR_PROXY_RESULT should be set", result);
        assertFalse("Should not be LOCAL", result.local());

        InetSocketAddress remoteAddr = (InetSocketAddress) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS);
        assertNotNull("ATTR_PROXY_REMOTE_ADDRESS should be set", remoteAddr);
        assertEquals(InetAddress.getByAddress(srcIp), remoteAddr.getAddress());
        assertEquals(srcPort, remoteAddr.getPort());

        InetSocketAddress localAddr = (InetSocketAddress) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_LOCAL_ADDRESS);
        assertNotNull("ATTR_PROXY_LOCAL_ADDRESS should be set", localAddr);
        assertEquals(InetAddress.getByAddress(dstIp), localAddr.getAddress());
        assertEquals(dstPort, localAddr.getPort());

        // Verify source and destination in the result record itself
        assertEquals(InetAddress.getByAddress(srcIp), result.sourceAddress().getAddress());
        assertEquals(srcPort, result.sourceAddress().getPort());
        assertEquals(InetAddress.getByAddress(dstIp), result.destinationAddress().getAddress());
        assertEquals(dstPort, result.destinationAddress().getPort());

        // No bytes should be forwarded (header consumed entirely)
        assertTrue("No message should be forwarded when header is consumed entirely",
                nextFilter.receivedMessages.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 2. Valid v2 IPv6 header
    // -----------------------------------------------------------------------

    @Test
    public void testValidV2IPv6Header() throws Exception {
        // Source: 2001:db8::1 port 54321, Destination: ::1 port 21
        byte[] srcIp = new byte[16];
        // 2001:0db8:0000:0000:0000:0000:0000:0001
        srcIp[0] = 0x20; srcIp[1] = 0x01;
        srcIp[2] = 0x0d; srcIp[3] = (byte) 0xb8;
        srcIp[15] = 0x01;

        byte[] dstIp = new byte[16];
        // ::1
        dstIp[15] = 0x01;

        int srcPort = 54321;
        int dstPort = 21;

        IoBuffer buf = buildV2Header(0x01, 0x21, srcIp, dstIp, srcPort, dstPort);
        filter.messageReceived(nextFilter, session, buf);

        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull("ATTR_PROXY_RESULT should be set", result);
        assertFalse("Should not be LOCAL", result.local());

        InetSocketAddress remoteAddr = (InetSocketAddress) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS);
        assertNotNull("ATTR_PROXY_REMOTE_ADDRESS should be set", remoteAddr);
        assertEquals(InetAddress.getByAddress(srcIp), remoteAddr.getAddress());
        assertEquals(srcPort, remoteAddr.getPort());

        InetSocketAddress localAddr = (InetSocketAddress) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_LOCAL_ADDRESS);
        assertNotNull("ATTR_PROXY_LOCAL_ADDRESS should be set", localAddr);
        assertEquals(InetAddress.getByAddress(dstIp), localAddr.getAddress());
        assertEquals(dstPort, localAddr.getPort());

        assertTrue("No message should be forwarded when header is consumed entirely",
                nextFilter.receivedMessages.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 3. Valid v2 header followed by additional data
    // -----------------------------------------------------------------------

    @Test
    public void testValidV2HeaderWithTrailingData() throws Exception {
        byte[] srcIp = {(byte) 192, (byte) 168, 0, 1};
        byte[] dstIp = {10, 0, 0, 2};
        int srcPort = 40000;
        int dstPort = 21;

        byte[] trailingData = "USER anonymous\r\n".getBytes(StandardCharsets.US_ASCII);

        IoBuffer headerBuf = buildV2Header(0x01, 0x11, srcIp, dstIp, srcPort, dstPort);
        // Append trailing data
        IoBuffer combined = IoBuffer.allocate(headerBuf.remaining() + trailingData.length);
        combined.put(headerBuf);
        combined.put(trailingData);
        combined.flip();

        filter.messageReceived(nextFilter, session, combined);

        // Header should have been parsed
        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull("ATTR_PROXY_RESULT should be set", result);
        assertFalse(result.local());

        // Trailing data should be forwarded
        assertEquals("Exactly one message should be forwarded", 1, nextFilter.receivedMessages.size());
        IoBuffer forwarded = (IoBuffer) nextFilter.receivedMessages.get(0);
        byte[] forwardedBytes = new byte[forwarded.remaining()];
        forwarded.get(forwardedBytes);
        assertArrayEquals("Forwarded bytes should be the trailing data", trailingData, forwardedBytes);
    }

    // -----------------------------------------------------------------------
    // 4. Non-PROXY data: FTP USER command
    // -----------------------------------------------------------------------

    @Test
    public void testNonProxyDataFtpCommand() throws Exception {
        // Must be >= 12 bytes so the filter has enough to check the signature
        byte[] ftpCommand = "USER anonymous\r\n".getBytes(StandardCharsets.US_ASCII);
        IoBuffer buf = IoBuffer.allocate(ftpCommand.length);
        buf.put(ftpCommand);
        buf.flip();

        filter.messageReceived(nextFilter, session, buf);

        // No proxy attributes should be set
        assertNull("ATTR_PROXY_RESULT should not be set",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_RESULT));
        assertNull("ATTR_PROXY_REMOTE_ADDRESS should not be set",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS));
        assertNull("ATTR_PROXY_LOCAL_ADDRESS should not be set",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_LOCAL_ADDRESS));

        // All bytes should be forwarded unchanged
        assertEquals("Exactly one message should be forwarded", 1, nextFilter.receivedMessages.size());
        IoBuffer forwarded = (IoBuffer) nextFilter.receivedMessages.get(0);
        byte[] forwardedBytes = new byte[forwarded.remaining()];
        forwarded.get(forwardedBytes);
        assertArrayEquals("All FTP command bytes should be forwarded unchanged", ftpCommand, forwardedBytes);
    }

    // -----------------------------------------------------------------------
    // 5. Non-PROXY data: TLS ClientHello
    // -----------------------------------------------------------------------

    @Test
    public void testNonProxyDataTlsClientHello() throws Exception {
        // TLS record: ContentType=Handshake (0x16), version, length, then ClientHello
        byte[] tlsRecord = new byte[]{
                0x16, 0x03, 0x01, 0x00, 0x05, // TLS record header
                0x01, 0x00, 0x00, 0x01, 0x00, // Minimal ClientHello stub
                0x03, 0x03                      // Extra bytes to surpass signature length
        };
        IoBuffer buf = IoBuffer.allocate(tlsRecord.length);
        buf.put(tlsRecord);
        buf.flip();

        filter.messageReceived(nextFilter, session, buf);

        // No proxy attributes should be set
        assertNull("ATTR_PROXY_RESULT should not be set",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_RESULT));
        assertNull("ATTR_PROXY_REMOTE_ADDRESS should not be set",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS));

        // All bytes should be forwarded unchanged
        assertEquals("Exactly one message should be forwarded", 1, nextFilter.receivedMessages.size());
        IoBuffer forwarded = (IoBuffer) nextFilter.receivedMessages.get(0);
        byte[] forwardedBytes = new byte[forwarded.remaining()];
        forwarded.get(forwardedBytes);
        assertArrayEquals("All TLS bytes should be forwarded unchanged", tlsRecord, forwardedBytes);
    }

    // -----------------------------------------------------------------------
    // 6. Fragmented delivery: header split across two messageReceived calls
    // -----------------------------------------------------------------------

    @Test
    public void testFragmentedDeliveryTwoParts() throws Exception {
        byte[] srcIp = {(byte) 172, 16, 0, 1};
        byte[] dstIp = {(byte) 172, 16, 0, 2};
        int srcPort = 55555;
        int dstPort = 21;

        IoBuffer fullHeader = buildV2Header(0x01, 0x11, srcIp, dstIp, srcPort, dstPort);
        byte[] fullBytes = new byte[fullHeader.remaining()];
        fullHeader.get(fullBytes);

        // Split: first 8 bytes, then remaining
        int splitPoint = 8;
        IoBuffer part1 = IoBuffer.allocate(splitPoint);
        part1.put(fullBytes, 0, splitPoint);
        part1.flip();

        IoBuffer part2 = IoBuffer.allocate(fullBytes.length - splitPoint);
        part2.put(fullBytes, splitPoint, fullBytes.length - splitPoint);
        part2.flip();

        // First call: should buffer and not forward anything
        filter.messageReceived(nextFilter, session, part1);
        assertTrue("No message should be forwarded after first fragment",
                nextFilter.receivedMessages.isEmpty());
        assertNull("ATTR_PROXY_RESULT should not be set yet",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_RESULT));

        // Second call: should complete parsing
        filter.messageReceived(nextFilter, session, part2);

        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull("ATTR_PROXY_RESULT should be set after second fragment", result);
        assertFalse(result.local());

        InetSocketAddress remoteAddr = (InetSocketAddress) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS);
        assertNotNull(remoteAddr);
        assertEquals(InetAddress.getByAddress(srcIp), remoteAddr.getAddress());
        assertEquals(srcPort, remoteAddr.getPort());

        // No trailing data, so no message forwarded
        assertTrue("No message should be forwarded (header only)",
                nextFilter.receivedMessages.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 7. Fragmented delivery: 16-byte header first, address block later
    // -----------------------------------------------------------------------

    @Test
    public void testFragmentedDeliverySignaturePlusLengthThenAddresses() throws Exception {
        byte[] srcIp = {10, 1, 2, 3};
        byte[] dstIp = {10, 4, 5, 6};
        int srcPort = 10000;
        int dstPort = 2121;

        IoBuffer fullHeader = buildV2Header(0x01, 0x11, srcIp, dstIp, srcPort, dstPort);
        byte[] fullBytes = new byte[fullHeader.remaining()];
        fullHeader.get(fullBytes);

        // Split at byte 16 (after the 16-byte fixed header, before addresses)
        int splitPoint = 16;
        IoBuffer part1 = IoBuffer.allocate(splitPoint);
        part1.put(fullBytes, 0, splitPoint);
        part1.flip();

        IoBuffer part2 = IoBuffer.allocate(fullBytes.length - splitPoint);
        part2.put(fullBytes, splitPoint, fullBytes.length - splitPoint);
        part2.flip();

        // First call: have signature + version/command + length, but not addresses
        filter.messageReceived(nextFilter, session, part1);
        assertTrue("No message should be forwarded after first fragment",
                nextFilter.receivedMessages.isEmpty());
        assertNull("ATTR_PROXY_RESULT should not be set yet",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_RESULT));

        // Second call: provide the address block
        filter.messageReceived(nextFilter, session, part2);

        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull("ATTR_PROXY_RESULT should be set after receiving addresses", result);

        InetSocketAddress remoteAddr = (InetSocketAddress) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS);
        assertNotNull(remoteAddr);
        assertEquals(InetAddress.getByAddress(srcIp), remoteAddr.getAddress());
        assertEquals(srcPort, remoteAddr.getPort());

        InetSocketAddress localAddr = (InetSocketAddress) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_LOCAL_ADDRESS);
        assertNotNull(localAddr);
        assertEquals(InetAddress.getByAddress(dstIp), localAddr.getAddress());
        assertEquals(dstPort, localAddr.getPort());

        assertTrue("No message should be forwarded (header only)",
                nextFilter.receivedMessages.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 8. LOCAL command (command=0x00)
    // -----------------------------------------------------------------------

    @Test
    public void testLocalCommand() throws Exception {
        // LOCAL command: version=2, command=0 -> ver_cmd = 0x20
        // family/proto can be 0x00 (AF_UNSPEC / UNSPEC), addr_len = 0
        IoBuffer buf = buildV2LocalHeader();
        filter.messageReceived(nextFilter, session, buf);

        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull("ATTR_PROXY_RESULT should be set for LOCAL command", result);
        assertTrue("Should be LOCAL", result.local());

        // No source/destination address attributes for LOCAL
        assertNull("ATTR_PROXY_REMOTE_ADDRESS should not be set for LOCAL",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS));
        assertNull("ATTR_PROXY_LOCAL_ADDRESS should not be set for LOCAL",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_LOCAL_ADDRESS));

        assertTrue("No message should be forwarded", nextFilter.receivedMessages.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 9. Invalid version (signature matches but version != 2)
    // -----------------------------------------------------------------------

    @Test
    public void testInvalidVersion() throws Exception {
        // Build a header with correct signature but version=3 instead of 2
        // ver_cmd byte: (3 << 4) | 0x01 = 0x31
        byte[] header = new byte[16 + 12]; // 16 header + 12 for IPv4 address block
        System.arraycopy(V2_SIGNATURE, 0, header, 0, 12);
        header[12] = 0x31; // version=3, command=PROXY
        header[13] = 0x11; // AF_INET, TCP
        header[14] = 0x00; // addr_len high byte
        header[15] = 0x0C; // addr_len low byte = 12
        // Fill some address bytes (doesn't matter what)
        for (int i = 16; i < header.length; i++) {
            header[i] = (byte) i;
        }

        IoBuffer buf = IoBuffer.allocate(header.length);
        buf.put(header);
        buf.flip();

        filter.messageReceived(nextFilter, session, buf);

        // No proxy attributes should be set
        assertNull("ATTR_PROXY_RESULT should not be set for invalid version",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_RESULT));
        assertNull("ATTR_PROXY_REMOTE_ADDRESS should not be set",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS));
        assertNull("ATTR_PROXY_LOCAL_ADDRESS should not be set",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_LOCAL_ADDRESS));

        // All bytes should be forwarded unchanged
        assertEquals("Exactly one message should be forwarded", 1, nextFilter.receivedMessages.size());
        IoBuffer forwarded = (IoBuffer) nextFilter.receivedMessages.get(0);
        byte[] forwardedBytes = new byte[forwarded.remaining()];
        forwarded.get(forwardedBytes);
        assertArrayEquals("All bytes should be forwarded unchanged on invalid version", header, forwardedBytes);
    }

    // -----------------------------------------------------------------------
    // 10. Already-processed session (second message after successful parse)
    // -----------------------------------------------------------------------

    @Test
    public void testAlreadyProcessedSessionPassesThrough() throws Exception {
        // First: send a valid proxy header
        byte[] srcIp = {(byte) 192, (byte) 168, 1, 1};
        byte[] dstIp = {10, 0, 0, 1};
        IoBuffer header = buildV2Header(0x01, 0x11, srcIp, dstIp, 12345, 21);
        filter.messageReceived(nextFilter, session, header);

        // Verify parsing happened
        assertNotNull(session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_RESULT));
        assertTrue("No trailing data, so no message forwarded after header",
                nextFilter.receivedMessages.isEmpty());

        // Now send a second message (e.g. FTP command)
        byte[] ftpCommand = "USER admin\r\n".getBytes(StandardCharsets.US_ASCII);
        IoBuffer secondMsg = IoBuffer.allocate(ftpCommand.length);
        secondMsg.put(ftpCommand);
        secondMsg.flip();

        filter.messageReceived(nextFilter, session, secondMsg);

        // The second message should pass through without any processing
        assertEquals("Second message should be forwarded as-is", 1, nextFilter.receivedMessages.size());
        IoBuffer forwarded = (IoBuffer) nextFilter.receivedMessages.get(0);
        // The forwarded message should be the exact same object (no wrapping)
        assertTrue("Forwarded message should be the same IoBuffer instance", forwarded == secondMsg);
    }

    // -----------------------------------------------------------------------
    // 11. Empty IoBuffer
    // -----------------------------------------------------------------------

    @Test
    public void testEmptyIoBuffer() throws Exception {
        IoBuffer buf = IoBuffer.allocate(0);
        // An empty buffer: position=0, limit=0, remaining=0
        buf.flip();

        filter.messageReceived(nextFilter, session, buf);

        // Nothing should crash; filter should be waiting for more data
        assertNull("ATTR_PROXY_RESULT should not be set",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_RESULT));
        assertTrue("No message should be forwarded", nextFilter.receivedMessages.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 12. Very small initial fragments (1 byte at a time)
    // -----------------------------------------------------------------------

    @Test
    public void testSingleByteFragments() throws Exception {
        byte[] srcIp = {(byte) 10, 0, 0, 1};
        byte[] dstIp = {(byte) 10, 0, 0, 2};
        int srcPort = 30000;
        int dstPort = 21;

        IoBuffer fullHeader = buildV2Header(0x01, 0x11, srcIp, dstIp, srcPort, dstPort);
        byte[] fullBytes = new byte[fullHeader.remaining()];
        fullHeader.get(fullBytes);

        // Feed bytes one at a time
        for (int i = 0; i < fullBytes.length - 1; i++) {
            IoBuffer singleByte = IoBuffer.allocate(1);
            singleByte.put(fullBytes[i]);
            singleByte.flip();

            filter.messageReceived(nextFilter, session, singleByte);

            // Should be buffering; no forwarding and no result yet
            assertTrue("No message should be forwarded during buffering (byte " + i + ")",
                    nextFilter.receivedMessages.isEmpty());
            assertNull("ATTR_PROXY_RESULT should not be set during buffering (byte " + i + ")",
                    session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_RESULT));
        }

        // Send the last byte to complete the header
        IoBuffer lastByte = IoBuffer.allocate(1);
        lastByte.put(fullBytes[fullBytes.length - 1]);
        lastByte.flip();

        filter.messageReceived(nextFilter, session, lastByte);

        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull("ATTR_PROXY_RESULT should be set after final byte", result);
        assertFalse(result.local());

        InetSocketAddress remoteAddr = (InetSocketAddress) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS);
        assertNotNull(remoteAddr);
        assertEquals(InetAddress.getByAddress(srcIp), remoteAddr.getAddress());
        assertEquals(srcPort, remoteAddr.getPort());

        // No trailing data
        assertTrue("No message should be forwarded (header only)",
                nextFilter.receivedMessages.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Additional edge case: non-IoBuffer message passes through
    // -----------------------------------------------------------------------

    @Test
    public void testNonIoBufferMessagePassesThrough() throws Exception {
        String stringMessage = "not an IoBuffer";
        filter.messageReceived(nextFilter, session, stringMessage);

        assertEquals("Non-IoBuffer message should be forwarded", 1, nextFilter.receivedMessages.size());
        assertEquals("Forwarded message should be the same object", stringMessage, nextFilter.receivedMessages.get(0));
    }

    // -----------------------------------------------------------------------
    // Additional: sessionClosed cleans up state
    // -----------------------------------------------------------------------

    @Test
    public void testSessionClosedCleansUpState() throws Exception {
        // Start detecting by sending a partial header
        IoBuffer partial = IoBuffer.allocate(4);
        partial.put(V2_SIGNATURE, 0, 4);
        partial.flip();

        filter.messageReceived(nextFilter, session, partial);

        // Verify internal state is set
        assertNotNull("Internal buffer should exist",
                session.getAttribute(ProxyProtocolFilter.class.getName() + ".buffer"));
        assertNotNull("Internal state should exist",
                session.getAttribute(ProxyProtocolFilter.class.getName() + ".state"));

        // Now close the session
        filter.sessionClosed(nextFilter, session);

        // Verify cleanup
        assertNull("Internal buffer should be removed after sessionClosed",
                session.getAttribute(ProxyProtocolFilter.class.getName() + ".buffer"));
        assertNull("Internal state should be removed after sessionClosed",
                session.getAttribute(ProxyProtocolFilter.class.getName() + ".state"));

        // Verify sessionClosed was forwarded
        assertTrue("sessionClosed should be forwarded to nextFilter",
                nextFilter.sessionClosedCalled);
    }

    // -----------------------------------------------------------------------
    // Additional: Valid v2 IPv6 header with trailing data
    // -----------------------------------------------------------------------

    @Test
    public void testValidV2IPv6HeaderWithTrailingData() throws Exception {
        byte[] srcIp = new byte[16];
        srcIp[0] = (byte) 0xFE; srcIp[1] = (byte) 0x80;
        srcIp[15] = 0x01;

        byte[] dstIp = new byte[16];
        dstIp[15] = 0x01;

        int srcPort = 60000;
        int dstPort = 990;

        byte[] trailingData = {0x16, 0x03, 0x03, 0x00, 0x10}; // TLS record start

        IoBuffer headerBuf = buildV2Header(0x01, 0x21, srcIp, dstIp, srcPort, dstPort);
        IoBuffer combined = IoBuffer.allocate(headerBuf.remaining() + trailingData.length);
        combined.put(headerBuf);
        combined.put(trailingData);
        combined.flip();

        filter.messageReceived(nextFilter, session, combined);

        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull(result);
        assertFalse(result.local());
        assertEquals(srcPort, result.sourceAddress().getPort());
        assertEquals(dstPort, result.destinationAddress().getPort());

        assertEquals("Trailing data should be forwarded", 1, nextFilter.receivedMessages.size());
        IoBuffer forwarded = (IoBuffer) nextFilter.receivedMessages.get(0);
        byte[] forwardedBytes = new byte[forwarded.remaining()];
        forwarded.get(forwardedBytes);
        assertArrayEquals(trailingData, forwardedBytes);
    }

    // -----------------------------------------------------------------------
    // Additional: LOCAL command with address bytes (should ignore them)
    // -----------------------------------------------------------------------

    @Test
    public void testLocalCommandWithAddressBytes() throws Exception {
        // LOCAL with some non-zero addr_len (spec says addresses should be ignored)
        byte[] header = new byte[16 + 12]; // 16 header + 12 address bytes
        System.arraycopy(V2_SIGNATURE, 0, header, 0, 12);
        header[12] = 0x20; // version=2, command=LOCAL (0x00)
        header[13] = 0x11; // AF_INET, TCP (shouldn't matter for LOCAL)
        header[14] = 0x00;
        header[15] = 0x0C; // addr_len = 12
        // Fill address bytes (should be ignored for LOCAL)
        for (int i = 16; i < header.length; i++) {
            header[i] = (byte) 0xFF;
        }

        IoBuffer buf = IoBuffer.allocate(header.length);
        buf.put(header);
        buf.flip();

        filter.messageReceived(nextFilter, session, buf);

        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull("ATTR_PROXY_RESULT should be set for LOCAL", result);
        assertTrue("Should be LOCAL", result.local());
        assertNull("No remote address for LOCAL",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_REMOTE_ADDRESS));
        assertNull("No local address for LOCAL",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_LOCAL_ADDRESS));
    }

    // -----------------------------------------------------------------------
    // Additional: Fragmented delivery where signature arrives, then mismatch
    //             is only detectable once we have 12 bytes
    // -----------------------------------------------------------------------

    @Test
    public void testFragmentedNonProxyFirstByteMatchesThenMismatch() throws Exception {
        // First 2 bytes match the signature: 0x0D 0x0A
        // But then a non-matching byte follows. We need at least 12 bytes
        // for the filter to decide, so send enough to trigger the check.
        byte[] data = new byte[]{
                0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A,
                0x51, 0x55, 0x49, 0x54, (byte) 0xFF, // 12th byte is 0xFF instead of 0x0A
                0x41, 0x42 // "AB"
        };

        IoBuffer buf = IoBuffer.allocate(data.length);
        buf.put(data);
        buf.flip();

        filter.messageReceived(nextFilter, session, buf);

        // Should pass through (signature mismatch at byte 11)
        assertNull("ATTR_PROXY_RESULT should not be set",
                session.getAttribute(ProxyProtocolFilter.ATTR_PROXY_RESULT));
        assertEquals(1, nextFilter.receivedMessages.size());
        IoBuffer forwarded = (IoBuffer) nextFilter.receivedMessages.get(0);
        byte[] forwardedBytes = new byte[forwarded.remaining()];
        forwarded.get(forwardedBytes);
        assertArrayEquals("All bytes should be forwarded on signature mismatch", data, forwardedBytes);
    }

    // -----------------------------------------------------------------------
    // Additional: Verify port encoding for high port numbers
    // -----------------------------------------------------------------------

    @Test
    public void testHighPortNumbers() throws Exception {
        byte[] srcIp = {127, 0, 0, 1};
        byte[] dstIp = {127, 0, 0, 1};
        int srcPort = 65535;
        int dstPort = 65534;

        IoBuffer buf = buildV2Header(0x01, 0x11, srcIp, dstIp, srcPort, dstPort);
        filter.messageReceived(nextFilter, session, buf);

        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull(result);
        assertEquals(srcPort, result.sourceAddress().getPort());
        assertEquals(dstPort, result.destinationAddress().getPort());
    }

    // -----------------------------------------------------------------------
    // Additional: Fragmented header + trailing data delivered in second chunk
    // -----------------------------------------------------------------------

    @Test
    public void testFragmentedWithTrailingDataInSecondChunk() throws Exception {
        byte[] srcIp = {(byte) 192, (byte) 168, 0, 50};
        byte[] dstIp = {10, 10, 10, 1};
        int srcPort = 11111;
        int dstPort = 21;
        byte[] trailingData = "FEAT\r\n".getBytes(StandardCharsets.US_ASCII);

        IoBuffer fullHeader = buildV2Header(0x01, 0x11, srcIp, dstIp, srcPort, dstPort);
        byte[] headerBytes = new byte[fullHeader.remaining()];
        fullHeader.get(headerBytes);

        // Part 1: first 14 bytes of the header
        IoBuffer part1 = IoBuffer.allocate(14);
        part1.put(headerBytes, 0, 14);
        part1.flip();

        // Part 2: remaining header bytes + trailing data
        byte[] part2Bytes = new byte[headerBytes.length - 14 + trailingData.length];
        System.arraycopy(headerBytes, 14, part2Bytes, 0, headerBytes.length - 14);
        System.arraycopy(trailingData, 0, part2Bytes, headerBytes.length - 14, trailingData.length);
        IoBuffer part2 = IoBuffer.allocate(part2Bytes.length);
        part2.put(part2Bytes);
        part2.flip();

        filter.messageReceived(nextFilter, session, part1);
        assertTrue("No forwarding after first partial fragment",
                nextFilter.receivedMessages.isEmpty());

        filter.messageReceived(nextFilter, session, part2);

        ProxyProtocolResult result = (ProxyProtocolResult) session.getAttribute(
                ProxyProtocolFilter.ATTR_PROXY_RESULT);
        assertNotNull(result);
        assertEquals(srcPort, result.sourceAddress().getPort());

        assertEquals("Trailing data should be forwarded", 1, nextFilter.receivedMessages.size());
        IoBuffer forwarded = (IoBuffer) nextFilter.receivedMessages.get(0);
        byte[] forwardedBytes = new byte[forwarded.remaining()];
        forwarded.get(forwardedBytes);
        assertArrayEquals("Trailing data should match", trailingData, forwardedBytes);
    }

    // -----------------------------------------------------------------------
    // Helper: Build a PROXY Protocol v2 header
    // -----------------------------------------------------------------------

    /**
     * Builds a complete PROXY Protocol v2 binary header.
     *
     * @param command  0x00 for LOCAL, 0x01 for PROXY
     * @param famProto (family << 4) | protocol. E.g. 0x11 for IPv4/TCP, 0x21 for IPv6/TCP
     * @param srcIp    source IP bytes (4 for IPv4, 16 for IPv6)
     * @param dstIp    destination IP bytes (4 for IPv4, 16 for IPv6)
     * @param srcPort  source port
     * @param dstPort  destination port
     * @return IoBuffer positioned at 0 with the complete header ready to read
     */
    private static IoBuffer buildV2Header(int command, int famProto,
                                          byte[] srcIp, byte[] dstIp,
                                          int srcPort, int dstPort) {
        int addrLen = srcIp.length + dstIp.length + 4; // +4 for two 2-byte ports
        int totalLen = 16 + addrLen; // 12 signature + 1 ver_cmd + 1 fam_proto + 2 length + addresses

        IoBuffer buf = IoBuffer.allocate(totalLen);

        // Signature (12 bytes)
        buf.put(V2_SIGNATURE);

        // Version (2) + command
        buf.put((byte) ((0x02 << 4) | (command & 0x0F)));

        // Family + protocol
        buf.put((byte) (famProto & 0xFF));

        // Address length (big-endian 16-bit)
        buf.put((byte) ((addrLen >> 8) & 0xFF));
        buf.put((byte) (addrLen & 0xFF));

        // Addresses
        buf.put(srcIp);
        buf.put(dstIp);

        // Ports (big-endian 16-bit)
        buf.put((byte) ((srcPort >> 8) & 0xFF));
        buf.put((byte) (srcPort & 0xFF));
        buf.put((byte) ((dstPort >> 8) & 0xFF));
        buf.put((byte) (dstPort & 0xFF));

        buf.flip();
        return buf;
    }

    /**
     * Builds a PROXY Protocol v2 LOCAL header with no address data.
     */
    private static IoBuffer buildV2LocalHeader() {
        int totalLen = 16; // 12 signature + 4 header bytes, addr_len=0
        IoBuffer buf = IoBuffer.allocate(totalLen);

        buf.put(V2_SIGNATURE);
        buf.put((byte) 0x20); // version=2, command=LOCAL(0)
        buf.put((byte) 0x00); // AF_UNSPEC, UNSPEC
        buf.put((byte) 0x00); // addr_len high
        buf.put((byte) 0x00); // addr_len low
        buf.flip();
        return buf;
    }

    // -----------------------------------------------------------------------
    // Simple capturing implementation of IoFilter.NextFilter
    // -----------------------------------------------------------------------

    /**
     * A minimal {@link IoFilter.NextFilter} implementation that captures
     * forwarded messages for verification in tests.
     */
    private static class CapturingNextFilter implements IoFilter.NextFilter {

        final List<Object> receivedMessages = new ArrayList<>();
        boolean sessionClosedCalled = false;

        @Override
        public void messageReceived(IoSession session, Object message) {
            receivedMessages.add(message);
        }

        @Override
        public void sessionClosed(IoSession session) {
            sessionClosedCalled = true;
        }

        @Override
        public void sessionCreated(IoSession session) {
        }

        @Override
        public void sessionOpened(IoSession session) {
        }

        @Override
        public void sessionIdle(IoSession session, IdleStatus status) {
        }

        @Override
        public void exceptionCaught(IoSession session, Throwable cause) {
        }

        @Override
        public void inputClosed(IoSession session) {
        }

        @Override
        public void messageSent(IoSession session, WriteRequest writeRequest) {
        }

        @Override
        public void filterWrite(IoSession session, WriteRequest writeRequest) {
        }

        @Override
        public void filterClose(IoSession session) {
        }

        @Override
        public void event(IoSession session, FilterEvent event) {
        }
    }
}
