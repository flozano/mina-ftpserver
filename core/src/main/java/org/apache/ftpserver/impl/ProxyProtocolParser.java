package org.apache.ftpserver.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses HAProxy PROXY Protocol v2 headers from sockets and streams.
 *
 * <p>Two modes of operation:</p>
 * <ul>
 *   <li>{@link #parseFromSocket(Socket)} — for data connections: reads the PROXY
 *       header directly from the socket's input stream. On success, the stream is
 *       positioned right after the header (no bytes lost). TLS wrapping of the
 *       underlying socket works correctly after this.</li>
 *   <li>{@link #parseFromStream(InputStream)} — for MINA filter: reads from a
 *       mark/reset-capable stream (IoBuffer-backed).</li>
 * </ul>
 */
public class ProxyProtocolParser {

	private static final Logger LOG = LoggerFactory.getLogger(ProxyProtocolParser.class);

	private static final byte[] V2_SIGNATURE = {
			0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A,
			0x51, 0x55, 0x49, 0x54, 0x0A
	};

	private static final int SIGNATURE_LENGTH = 12;
	private static final int HEADER_LENGTH = 16;

	/**
	 * Reads a PROXY Protocol v2 header directly from a socket's input stream.
	 * After a successful parse, the socket's stream is positioned right after
	 * the header — subsequent reads (including TLS handshake) work normally.
	 *
	 * <p>This reads bytes directly from the raw stream without buffering,
	 * so the socket can be safely wrapped with TLS afterwards.</p>
	 *
	 * @param socket the accepted socket
	 * @return the parsed result, or null if parsing failed
	 * @throws IOException if reading fails
	 */
	public static ProxyProtocolResult parseFromSocket(Socket socket) throws IOException {
		InputStream in = socket.getInputStream();

		// Read the signature (12 bytes) directly from the raw stream.
		byte[] sigBuf = new byte[SIGNATURE_LENGTH];
		if (readFully(in, sigBuf, SIGNATURE_LENGTH) < SIGNATURE_LENGTH) {
			LOG.warn("PROXY protocol: insufficient bytes for signature on data connection");
			return null;
		}
		if (!matchesSignature(sigBuf)) {
			LOG.warn("PROXY protocol: signature mismatch on data connection");
			return null;
		}

		// Read version+command, family+protocol, length (4 bytes)
		byte[] hdrBuf = new byte[4];
		if (readFully(in, hdrBuf, 4) < 4) {
			LOG.warn("PROXY protocol: insufficient bytes for header on data connection");
			return null;
		}

		int verCmd = hdrBuf[0] & 0xFF;
		int version = (verCmd >> 4) & 0x0F;
		int command = verCmd & 0x0F;

		if (version != 2) {
			LOG.warn("PROXY protocol: unexpected version {} on data connection", version);
			return null;
		}

		int famProto = hdrBuf[1] & 0xFF;
		int addrLen = ((hdrBuf[2] & 0xFF) << 8) | (hdrBuf[3] & 0xFF);

		// Read the address block
		byte[] addrBuf = new byte[addrLen];
		if (readFully(in, addrBuf, addrLen) < addrLen) {
			LOG.warn("PROXY protocol: insufficient bytes for address block on data connection");
			return null;
		}

		return parseAddresses(command, famProto, addrBuf, addrLen);
	}

	/**
	 * Attempts to read a PROXY Protocol v2 header from a mark/reset stream.
	 * Used by the MINA IoFilter for best-effort detection on control connections.
	 *
	 * @param in a mark-supported InputStream
	 * @return the parsed result, or null if no PROXY header detected
	 */
	public static ProxyProtocolResult parseFromStream(InputStream in) throws IOException {
		if (!in.markSupported()) {
			throw new IllegalArgumentException("InputStream must support mark/reset");
		}

		in.mark(HEADER_LENGTH + 216);

		byte[] sigBuf = new byte[SIGNATURE_LENGTH];
		if (readFully(in, sigBuf, SIGNATURE_LENGTH) < SIGNATURE_LENGTH || !matchesSignature(sigBuf)) {
			in.reset();
			return null;
		}

		byte[] hdrBuf = new byte[4];
		if (readFully(in, hdrBuf, 4) < 4) {
			in.reset();
			return null;
		}

		int verCmd = hdrBuf[0] & 0xFF;
		int version = (verCmd >> 4) & 0x0F;
		int command = verCmd & 0x0F;

		if (version != 2) {
			LOG.warn("PROXY protocol: signature matched but version {} (expected 2)", version);
			in.reset();
			return null;
		}

		int famProto = hdrBuf[1] & 0xFF;
		int addrLen = ((hdrBuf[2] & 0xFF) << 8) | (hdrBuf[3] & 0xFF);

		byte[] addrBuf = new byte[addrLen];
		if (readFully(in, addrBuf, addrLen) < addrLen) {
			in.reset();
			return null;
		}

		return parseAddresses(command, famProto, addrBuf, addrLen);
	}

	private static ProxyProtocolResult parseAddresses(int command, int famProto, byte[] addrBuf, int addrLen) {
		boolean local = (command == 0x00);
		if (local) {
			return new ProxyProtocolResult(null, null, true);
		}
		if (command != 0x01) {
			LOG.warn("PROXY protocol v2: unknown command 0x{}", Integer.toHexString(command));
			return null;
		}

		int addrFamily = (famProto >> 4) & 0x0F;
		try {
			return switch (addrFamily) {
				case 0x01 -> parseIPv4(addrBuf, addrLen);
				case 0x02 -> parseIPv6(addrBuf, addrLen);
				case 0x00 -> new ProxyProtocolResult(null, null, false);
				default -> {
					LOG.warn("PROXY protocol v2: unsupported address family 0x{}", Integer.toHexString(addrFamily));
					yield null;
				}
			};
		} catch (Exception e) {
			LOG.error("Failed to parse PROXY protocol addresses", e);
			return null;
		}
	}

	private static ProxyProtocolResult parseIPv4(byte[] buf, int len) throws Exception {
		if (len < 12) return null;
		byte[] srcIp = new byte[4];
		byte[] dstIp = new byte[4];
		System.arraycopy(buf, 0, srcIp, 0, 4);
		System.arraycopy(buf, 4, dstIp, 0, 4);
		int srcPort = ((buf[8] & 0xFF) << 8) | (buf[9] & 0xFF);
		int dstPort = ((buf[10] & 0xFF) << 8) | (buf[11] & 0xFF);
		return new ProxyProtocolResult(
				new InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
				new InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort),
				false);
	}

	private static ProxyProtocolResult parseIPv6(byte[] buf, int len) throws Exception {
		if (len < 36) return null;
		byte[] srcIp = new byte[16];
		byte[] dstIp = new byte[16];
		System.arraycopy(buf, 0, srcIp, 0, 16);
		System.arraycopy(buf, 16, dstIp, 0, 16);
		int srcPort = ((buf[32] & 0xFF) << 8) | (buf[33] & 0xFF);
		int dstPort = ((buf[34] & 0xFF) << 8) | (buf[35] & 0xFF);
		return new ProxyProtocolResult(
				new InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
				new InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort),
				false);
	}

	private static boolean matchesSignature(byte[] buf) {
		for (int i = 0; i < SIGNATURE_LENGTH; i++) {
			if (buf[i] != V2_SIGNATURE[i]) return false;
		}
		return true;
	}

	private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
		int offset = 0;
		while (offset < len) {
			int n = in.read(buf, offset, len - offset);
			if (n < 0) break;
			offset += n;
		}
		return offset;
	}
}
