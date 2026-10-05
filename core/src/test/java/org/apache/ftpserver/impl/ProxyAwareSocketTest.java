/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ftpserver.impl;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ProxyAwareSocket} — best-effort PROXY Protocol v2
 * detection on raw sockets using real loopback connections.
 */
public class ProxyAwareSocketTest {

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private Socket acceptedSocket;
    private ProxyAwareSocket proxyAwareSocket;

    @After
    public void tearDown() throws Exception {
        closeQuietly(proxyAwareSocket);
        closeQuietly(acceptedSocket);
        closeQuietly(clientSocket);
        closeQuietly(serverSocket);
    }

    // ------------------------------------------------------------------
    // PROXY header detection tests
    // ------------------------------------------------------------------

    @Test
    public void validV2IPv4Header_addressesOverridden() throws Exception {
        byte[] srcIp = {10, 0, 0, 1};
        byte[] dstIp = {10, 0, 0, 2};
        int srcPort = 12345;
        int dstPort = 21;

        byte[] header = buildV2Header(0x21, 0x11, srcIp, dstIp, srcPort, dstPort);
        byte[] payload = "HELLO\n".getBytes();
        byte[] data = concat(header, payload);

        ProxyAwareSocket pas = acceptWithData(data);

        assertNotNull("PROXY result should be present", pas.getProxyResult());
        assertFalse("Should not be LOCAL", pas.getProxyResult().local());
        assertEquals(InetAddress.getByAddress(srcIp), pas.getInetAddress());
        assertEquals(srcPort, pas.getPort());
        assertEquals(InetAddress.getByAddress(srcIp), pas.getProxyResult().sourceAddress().getAddress());
        assertEquals(srcPort, pas.getProxyResult().sourceAddress().getPort());
        assertEquals(InetAddress.getByAddress(dstIp), pas.getProxyResult().destinationAddress().getAddress());
        assertEquals(dstPort, pas.getProxyResult().destinationAddress().getPort());

        // Payload should be intact after the consumed header
        byte[] readPayload = readAllAvailable(pas.getInputStream(), payload.length);
        assertArrayEquals("Payload after PROXY header must be intact", payload, readPayload);
    }

    @Test
    public void validV2IPv6Header_addressesOverridden() throws Exception {
        // Source: 2001:db8::1, Dest: 2001:db8::2
        byte[] srcIp = new byte[16];
        srcIp[0] = 0x20; srcIp[1] = 0x01; srcIp[2] = 0x0d; srcIp[3] = (byte) 0xb8;
        srcIp[15] = 0x01;

        byte[] dstIp = new byte[16];
        dstIp[0] = 0x20; dstIp[1] = 0x01; dstIp[2] = 0x0d; dstIp[3] = (byte) 0xb8;
        dstIp[15] = 0x02;

        int srcPort = 54321;
        int dstPort = 21;

        byte[] header = buildV2Header(0x21, 0x21, srcIp, dstIp, srcPort, dstPort);
        byte[] payload = "IPV6DATA\n".getBytes();
        byte[] data = concat(header, payload);

        ProxyAwareSocket pas = acceptWithData(data);

        assertNotNull("PROXY result should be present", pas.getProxyResult());
        assertFalse("Should not be LOCAL", pas.getProxyResult().local());
        assertEquals(InetAddress.getByAddress(srcIp), pas.getInetAddress());
        assertEquals(srcPort, pas.getPort());

        byte[] readPayload = readAllAvailable(pas.getInputStream(), payload.length);
        assertArrayEquals("Payload after PROXY header must be intact", payload, readPayload);
    }

    @Test
    public void validV2HeaderFollowedByTlsClientHello() throws Exception {
        byte[] srcIp = {(byte) 192, (byte) 168, 1, 100};
        byte[] dstIp = {(byte) 192, (byte) 168, 1, 1};
        byte[] header = buildV2Header(0x21, 0x11, srcIp, dstIp, 443, 8021);

        // TLS ClientHello record type starts with 0x16
        byte[] tlsBytes = {0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00};
        byte[] data = concat(header, tlsBytes);

        ProxyAwareSocket pas = acceptWithData(data);

        assertNotNull("PROXY result should be present", pas.getProxyResult());

        // First byte from stream should be 0x16 (TLS record type)
        int firstByte = pas.getInputStream().read();
        assertEquals("First data byte after PROXY header should be TLS record type",
                0x16, firstByte);
    }

    @Test
    public void validV2LocalCommand_noAddressOverride() throws Exception {
        // LOCAL command = 0x20 (version 2, command 0)
        byte[] header = buildV2HeaderRaw(0x20, 0x00, new byte[0]);

        ProxyAwareSocket pas = acceptWithData(header);

        assertNotNull("PROXY result should be present", pas.getProxyResult());
        assertTrue("Should be LOCAL", pas.getProxyResult().local());

        // getInetAddress() should return the actual socket address (no override for LOCAL)
        assertEquals("LOCAL command should not override address",
                acceptedSocket.getInetAddress(), pas.getInetAddress());
    }

    // ------------------------------------------------------------------
    // Non-PROXY passthrough (best-effort detection)
    // ------------------------------------------------------------------

    @Test
    public void nonProxyData_ftpCommand_noByteLoss() throws Exception {
        byte[] ftpData = "USER test\r\n".getBytes();

        ProxyAwareSocket pas = acceptWithData(ftpData);

        assertNull("No PROXY result expected", pas.getProxyResult());
        assertEquals("Address should be the socket address",
                acceptedSocket.getInetAddress(), pas.getInetAddress());

        byte[] readData = readAllAvailable(pas.getInputStream(), ftpData.length);
        assertArrayEquals("FTP command bytes must not be lost", ftpData, readData);
    }

    @Test
    public void nonProxyData_tlsClientHello_noByteLoss() throws Exception {
        // TLS record: type=0x16, version=0x0301, length=5, handshake data
        byte[] tlsData = {0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00};

        ProxyAwareSocket pas = acceptWithData(tlsData);

        assertNull("No PROXY result expected for TLS data", pas.getProxyResult());

        byte[] readData = readAllAvailable(pas.getInputStream(), tlsData.length);
        assertArrayEquals("TLS bytes must not be lost", tlsData, readData);
    }

    @Test
    public void nonProxyData_emptyConnection_noByteLoss() throws Exception {
        // Client connects and immediately closes (EOF)
        setupLoopback();
        clientSocket.getOutputStream().close();
        clientSocket.close();

        proxyAwareSocket = new ProxyAwareSocket(acceptedSocket);

        assertNull("No PROXY result expected for empty connection", proxyAwareSocket.getProxyResult());
        assertEquals("Address should be the socket address",
                acceptedSocket.getInetAddress(), proxyAwareSocket.getInetAddress());
    }

    @Test
    public void nonProxyData_singleByte_noByteLoss() throws Exception {
        byte[] singleByte = {0x41}; // 'A'

        ProxyAwareSocket pas = acceptWithData(singleByte);

        assertNull("No PROXY result expected for single byte", pas.getProxyResult());

        int read = pas.getInputStream().read();
        assertEquals("Single byte 'A' must be readable", 0x41, read);
    }

    // ------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------

    @Test
    public void partialSignatureMatch_noByteLoss() throws Exception {
        // Send 11 bytes matching the signature prefix + 1 different byte + "DATA"
        byte[] v2Sig = {
                0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A,
                0x51, 0x55, 0x49, 0x54, 0x0A
        };

        // 11 matching bytes + 1 wrong byte
        byte[] partial = new byte[12];
        System.arraycopy(v2Sig, 0, partial, 0, 11);
        partial[11] = (byte) 0xFF; // wrong 12th byte

        byte[] trailingData = "DATA".getBytes();
        byte[] data = concat(partial, trailingData);

        ProxyAwareSocket pas = acceptWithData(data);

        assertNull("No PROXY result expected for partial signature", pas.getProxyResult());

        byte[] readData = readAllAvailable(pas.getInputStream(), data.length);
        assertArrayEquals("All bytes must be pushed back and readable", data, readData);
    }

    @Test
    public void signatureMatchButInvalidVersion_noByteLoss() throws Exception {
        // Full 12-byte signature + version 0x30 (version 3, command 0) — wrong version
        byte[] v2Sig = {
                0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A,
                0x51, 0x55, 0x49, 0x54, 0x0A
        };
        // ver_cmd = 0x30 (version 3), fam_proto = 0x11, addr_len = 12
        byte[] hdr = {0x30, 0x11, 0x00, 0x0C};
        // 12 bytes of address data
        byte[] addrBlock = new byte[12];
        Arrays.fill(addrBlock, (byte) 0xAA);

        byte[] trailingData = "EXTRA".getBytes();
        byte[] data = concat(concat(concat(v2Sig, hdr), addrBlock), trailingData);

        ProxyAwareSocket pas = acceptWithData(data);

        assertNull("No PROXY result expected for wrong version", pas.getProxyResult());

        // All 12 (sig) + 4 (hdr) = 16 bytes should be pushed back, but the address block
        // was never read because version check fails after reading sig+hdr.
        // Actually: sig (12) + hdr (4) = 16 bytes pushed back, addr block + trailing still in stream.
        // Total readable = 16 pushed back + 12 addr + 5 trailing = 33
        byte[] readData = readAllAvailable(pas.getInputStream(), data.length);
        assertArrayEquals("All bytes must be readable after version mismatch", data, readData);
    }

    @Test
    public void signatureMatchButConnectionClosedBeforeHeader_graceful() throws Exception {
        // Send only the 12 signature bytes then close
        byte[] v2Sig = {
                0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A,
                0x51, 0x55, 0x49, 0x54, 0x0A
        };

        setupLoopback();
        OutputStream out = clientSocket.getOutputStream();
        out.write(v2Sig);
        out.flush();
        clientSocket.close();

        proxyAwareSocket = new ProxyAwareSocket(acceptedSocket);

        // Should handle gracefully — no exception, no NPE
        assertNull("No PROXY result for truncated header", proxyAwareSocket.getProxyResult());

        // The 12 signature bytes should be pushed back
        byte[] readData = readAllAvailable(proxyAwareSocket.getInputStream(), v2Sig.length);
        assertArrayEquals("Signature bytes should be pushed back", v2Sig, readData);
    }

    @Test
    public void validHeaderFollowedByLargeData_allDataIntact() throws Exception {
        byte[] srcIp = {10, 0, 0, 1};
        byte[] dstIp = {10, 0, 0, 2};
        byte[] header = buildV2Header(0x21, 0x11, srcIp, dstIp, 9999, 21);

        // 64KB of data
        byte[] largePayload = new byte[65536];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i & 0xFF);
        }

        byte[] data = concat(header, largePayload);

        setupLoopback();
        // Write in a separate thread since 64KB+ might block
        Thread writer = new Thread(() -> {
            try {
                OutputStream out = clientSocket.getOutputStream();
                out.write(data);
                out.flush();
                clientSocket.shutdownOutput();
            } catch (IOException e) {
                // ignore
            }
        });
        writer.start();

        proxyAwareSocket = new ProxyAwareSocket(acceptedSocket);
        writer.join(5000);

        assertNotNull("PROXY result should be present", proxyAwareSocket.getProxyResult());

        // Read all the payload data
        byte[] readPayload = readAllFromStream(proxyAwareSocket.getInputStream());
        assertArrayEquals("64KB payload must be intact after PROXY header", largePayload, readPayload);
    }

    @Test
    public void getOriginalInetAddress_alwaysReturnsSocketAddress() throws Exception {
        byte[] srcIp = {10, 0, 0, 1};
        byte[] dstIp = {10, 0, 0, 2};
        byte[] header = buildV2Header(0x21, 0x11, srcIp, dstIp, 12345, 21);
        byte[] data = concat(header, "X".getBytes());

        ProxyAwareSocket pas = acceptWithData(data);

        assertNotNull("PROXY result should be present", pas.getProxyResult());

        // getOriginalInetAddress() should always return the actual socket address
        assertEquals("getOriginalInetAddress should return socket address",
                acceptedSocket.getInetAddress(), pas.getOriginalInetAddress());

        // getInetAddress() should return the PROXY source address
        assertEquals("getInetAddress should return PROXY source address",
                InetAddress.getByAddress(srcIp), pas.getInetAddress());

        // They should be different (PROXY source vs loopback)
        assertFalse("Original and PROXY addresses should differ",
                pas.getOriginalInetAddress().equals(pas.getInetAddress()));
    }

    @Test
    public void otherSocketMethodsDelegate() throws Exception {
        byte[] ftpData = "NOOP\r\n".getBytes();
        ProxyAwareSocket pas = acceptWithData(ftpData);

        // getOutputStream should work
        assertNotNull("getOutputStream should delegate", pas.getOutputStream());

        // getLocalAddress should delegate
        assertEquals("getLocalAddress should delegate",
                acceptedSocket.getLocalAddress(), pas.getLocalAddress());

        // getLocalPort should delegate
        assertEquals("getLocalPort should delegate",
                acceptedSocket.getLocalPort(), pas.getLocalPort());

        // isClosed should delegate
        assertFalse("isClosed should be false before close", pas.isClosed());

        // close should delegate
        pas.close();
        assertTrue("isClosed should be true after close", pas.isClosed());
    }

    // ------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------

    /**
     * Sets up a loopback ServerSocket, connects a client, and accepts the
     * server-side socket.
     */
    private void setupLoopback() throws IOException {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        clientSocket = new Socket();
        clientSocket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                serverSocket.getLocalPort()));
        acceptedSocket = serverSocket.accept();
    }

    /**
     * Convenience: sets up loopback, sends data from client, wraps in
     * ProxyAwareSocket and returns it. Also stores references for cleanup.
     */
    private ProxyAwareSocket acceptWithData(byte[] data) throws IOException {
        setupLoopback();
        OutputStream out = clientSocket.getOutputStream();
        out.write(data);
        out.flush();
        // Shut down output so the server side can detect EOF for reads
        clientSocket.shutdownOutput();

        proxyAwareSocket = new ProxyAwareSocket(acceptedSocket);
        return proxyAwareSocket;
    }

    /**
     * Reads exactly {@code length} bytes from the stream.
     */
    private byte[] readAllAvailable(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(buf, offset, length - offset);
            if (n < 0) break;
            offset += n;
        }
        if (offset < length) {
            return Arrays.copyOf(buf, offset);
        }
        return buf;
    }

    /**
     * Reads all bytes from the stream until EOF.
     */
    private byte[] readAllFromStream(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        int totalRead = 0;
        byte[] result = new byte[0];
        int n;
        while ((n = in.read(buf)) >= 0) {
            byte[] newResult = new byte[totalRead + n];
            System.arraycopy(result, 0, newResult, 0, totalRead);
            System.arraycopy(buf, 0, newResult, totalRead, n);
            result = newResult;
            totalRead += n;
        }
        return result;
    }

    /**
     * Builds a PROXY Protocol v2 header for IPv4 or IPv6.
     *
     * @param verCmd  version+command byte (e.g. 0x21 for PROXY command)
     * @param famProto family+protocol byte (e.g. 0x11 for TCP/IPv4, 0x21 for TCP/IPv6)
     * @param srcIp  source IP bytes (4 for IPv4, 16 for IPv6)
     * @param dstIp  destination IP bytes
     * @param srcPort source port
     * @param dstPort destination port
     * @return complete PROXY v2 header bytes
     */
    private byte[] buildV2Header(int verCmd, int famProto,
                                 byte[] srcIp, byte[] dstIp,
                                 int srcPort, int dstPort) {
        int addrLen = srcIp.length + dstIp.length + 4; // +4 for two ports
        byte[] addrBlock = new byte[addrLen];

        System.arraycopy(srcIp, 0, addrBlock, 0, srcIp.length);
        System.arraycopy(dstIp, 0, addrBlock, srcIp.length, dstIp.length);

        int portOffset = srcIp.length + dstIp.length;
        addrBlock[portOffset] = (byte) ((srcPort >> 8) & 0xFF);
        addrBlock[portOffset + 1] = (byte) (srcPort & 0xFF);
        addrBlock[portOffset + 2] = (byte) ((dstPort >> 8) & 0xFF);
        addrBlock[portOffset + 3] = (byte) (dstPort & 0xFF);

        return buildV2HeaderRaw(verCmd, famProto, addrBlock);
    }

    /**
     * Builds a PROXY Protocol v2 header with a raw address block.
     */
    private byte[] buildV2HeaderRaw(int verCmd, int famProto, byte[] addrBlock) {
        byte[] signature = {
                0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A,
                0x51, 0x55, 0x49, 0x54, 0x0A
        };

        int totalLen = 12 + 4 + addrBlock.length;
        byte[] header = new byte[totalLen];

        // Signature (12 bytes)
        System.arraycopy(signature, 0, header, 0, 12);

        // ver_cmd
        header[12] = (byte) verCmd;

        // fam_proto
        header[13] = (byte) famProto;

        // address length (big-endian)
        header[14] = (byte) ((addrBlock.length >> 8) & 0xFF);
        header[15] = (byte) (addrBlock.length & 0xFF);

        // address block
        System.arraycopy(addrBlock, 0, header, 16, addrBlock.length);

        return header;
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}
