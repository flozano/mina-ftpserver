package org.apache.ftpserver.listener.nio;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.apache.ftpserver.impl.ProxyProtocolResult;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MINA IoFilter that performs best-effort detection and parsing of
 * HAProxy PROXY Protocol v2 headers.
 *
 * <p>When placed at the front of the filter chain, this filter buffers
 * the first bytes received on a new connection and checks whether they
 * match the PROXY Protocol v2 binary signature. If they do, the header
 * is parsed and the original client/server addresses are stored as
 * session attributes. If not, the buffered bytes are replayed downstream
 * unmodified.</p>
 *
 * <p>This filter supports best-effort / auto-detect mode: the same
 * listener can accept both proxied and direct connections.</p>
 */
public class ProxyProtocolFilter extends IoFilterAdapter {

	private static final Logger LOG = LoggerFactory.getLogger(ProxyProtocolFilter.class);

	/** Session attribute key for the parsed PROXY protocol result. */
	public static final String ATTR_PROXY_RESULT = "org.apache.ftpserver.proxy-protocol-result";

	/** Session attribute key for the PROXY-provided remote (client) address. */
	public static final String ATTR_PROXY_REMOTE_ADDRESS = "org.apache.ftpserver.proxy-remote-address";

	/** Session attribute key for the PROXY-provided local (server) address. */
	public static final String ATTR_PROXY_LOCAL_ADDRESS = "org.apache.ftpserver.proxy-local-address";

	private static final String ATTR_STATE = ProxyProtocolFilter.class.getName() + ".state";
	private static final String ATTR_BUFFER = ProxyProtocolFilter.class.getName() + ".buffer";

	/** PROXY Protocol v2 12-byte signature. */
	private static final byte[] V2_SIGNATURE = {
			0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A,
			0x51, 0x55, 0x49, 0x54, 0x0A
	};

	private static final int SIGNATURE_LENGTH = 12;
	private static final int HEADER_LENGTH = 16; // signature(12) + ver_cmd(1) + fam_proto(1) + length(2)

	private enum State {
		DETECTING, DONE
	}

	@Override
	public void messageReceived(NextFilter nextFilter, IoSession session, Object message) throws Exception {
		LOG.debug("ProxyProtocolFilter.messageReceived: message type={}, state={}",
				message != null ? message.getClass().getSimpleName() : "null",
				session.getAttribute(ATTR_STATE));
		if (!(message instanceof IoBuffer incoming)) {
			nextFilter.messageReceived(session, message);
			return;
		}

		State state = (State) session.getAttribute(ATTR_STATE);
		if (state == State.DONE) {
			nextFilter.messageReceived(session, message);
			return;
		}

		// Accumulate bytes
		IoBuffer cumBuf = (IoBuffer) session.getAttribute(ATTR_BUFFER);
		if (cumBuf == null) {
			cumBuf = IoBuffer.allocate(256).setAutoExpand(true);
			session.setAttribute(ATTR_BUFFER, cumBuf);
			session.setAttribute(ATTR_STATE, State.DETECTING);
		}
		cumBuf.put(incoming);
		cumBuf.flip();

		int available = cumBuf.remaining();

		// Need at least the signature to decide
		if (available < SIGNATURE_LENGTH) {
			// Compact and wait for more data
			cumBuf.compact();
			return;
		}

		// Check if the first 12 bytes match the v2 signature
		if (!matchesSignature(cumBuf)) {
			// Not a PROXY header — replay all buffered bytes downstream
			LOG.debug("No PROXY protocol v2 header detected, passing through");
			finish(session);
			nextFilter.messageReceived(session, cumBuf);
			return;
		}

		// Signature matches — need full header (16 bytes) to read the length field
		if (available < HEADER_LENGTH) {
			cumBuf.compact();
			return;
		}

		// Read version+command and family+protocol
		int verCmd = cumBuf.get(SIGNATURE_LENGTH) & 0xFF;
		int version = (verCmd >> 4) & 0x0F;
		int command = verCmd & 0x0F;

		if (version != 2) {
			LOG.warn("PROXY protocol signature matched but version is {} (expected 2), passing through", version);
			finish(session);
			cumBuf.position(0);
			nextFilter.messageReceived(session, cumBuf);
			return;
		}

		int famProto = cumBuf.get(SIGNATURE_LENGTH + 1) & 0xFF;
		int addrLen = ((cumBuf.get(SIGNATURE_LENGTH + 2) & 0xFF) << 8)
				| (cumBuf.get(SIGNATURE_LENGTH + 3) & 0xFF);

		int totalHeaderLen = HEADER_LENGTH + addrLen;

		// Wait for the full header + address block
		if (available < totalHeaderLen) {
			cumBuf.compact();
			return;
		}

		// Parse the header
		ProxyProtocolResult result = parseHeader(command, famProto, cumBuf, addrLen);
		if (result != null) {
			session.setAttribute(ATTR_PROXY_RESULT, result);
			if (!result.local() && result.sourceAddress() != null) {
				session.setAttribute(ATTR_PROXY_REMOTE_ADDRESS, result.sourceAddress());
			}
			if (!result.local() && result.destinationAddress() != null) {
				session.setAttribute(ATTR_PROXY_LOCAL_ADDRESS, result.destinationAddress());
			}
			LOG.info("PROXY protocol v2: {}", result);
		}

		finish(session);

		// Forward remaining bytes (e.g., TLS ClientHello or FTP command)
		cumBuf.position(totalHeaderLen);
		if (cumBuf.hasRemaining()) {
			IoBuffer remaining = IoBuffer.allocate(cumBuf.remaining());
			remaining.put(cumBuf);
			remaining.flip();
			nextFilter.messageReceived(session, remaining);
		}
	}

	@Override
	public void sessionClosed(NextFilter nextFilter, IoSession session) throws Exception {
		// Clean up buffer if still detecting
		session.removeAttribute(ATTR_BUFFER);
		session.removeAttribute(ATTR_STATE);
		nextFilter.sessionClosed(session);
	}

	private void finish(IoSession session) {
		session.setAttribute(ATTR_STATE, State.DONE);
		session.removeAttribute(ATTR_BUFFER);
	}

	private static boolean matchesSignature(IoBuffer buf) {
		for (int i = 0; i < SIGNATURE_LENGTH; i++) {
			if (buf.get(i) != V2_SIGNATURE[i]) {
				return false;
			}
		}
		return true;
	}

	private static ProxyProtocolResult parseHeader(int command, int famProto, IoBuffer buf, int addrLen) {
		boolean local = (command == 0x00);

		if (local) {
			// LOCAL command — no address data meaningful
			return new ProxyProtocolResult(null, null, true);
		}

		if (command != 0x01) {
			LOG.warn("PROXY protocol v2: unknown command 0x{}, ignoring", Integer.toHexString(command));
			return null;
		}

		int addrFamily = (famProto >> 4) & 0x0F;
		// int transport = famProto & 0x0F; // TCP=1, UDP=2 — we don't need this

		int pos = HEADER_LENGTH; // Start reading addresses after the 16-byte header

		try {
			switch (addrFamily) {
				case 0x01: // AF_INET (IPv4)
					if (addrLen < 12) {
						LOG.warn("PROXY protocol v2: IPv4 address block too short ({})", addrLen);
						return null;
					}
					byte[] srcIp4 = new byte[4];
					byte[] dstIp4 = new byte[4];
					buf.position(pos);
					buf.get(srcIp4);
					buf.get(dstIp4);
					int srcPort4 = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
					int dstPort4 = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
					return new ProxyProtocolResult(
							new InetSocketAddress(InetAddress.getByAddress(srcIp4), srcPort4),
							new InetSocketAddress(InetAddress.getByAddress(dstIp4), dstPort4),
							false);

				case 0x02: // AF_INET6 (IPv6)
					if (addrLen < 36) {
						LOG.warn("PROXY protocol v2: IPv6 address block too short ({})", addrLen);
						return null;
					}
					byte[] srcIp6 = new byte[16];
					byte[] dstIp6 = new byte[16];
					buf.position(pos);
					buf.get(srcIp6);
					buf.get(dstIp6);
					int srcPort6 = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
					int dstPort6 = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
					return new ProxyProtocolResult(
							new InetSocketAddress(InetAddress.getByAddress(srcIp6), srcPort6),
							new InetSocketAddress(InetAddress.getByAddress(dstIp6), dstPort6),
							false);

				case 0x00: // AF_UNSPEC
					LOG.debug("PROXY protocol v2: AF_UNSPEC, no addresses");
					return new ProxyProtocolResult(null, null, false);

				default:
					LOG.warn("PROXY protocol v2: unsupported address family 0x{}", Integer.toHexString(addrFamily));
					return null;
			}
		} catch (UnknownHostException e) {
			// Should never happen with InetAddress.getByAddress()
			LOG.error("Failed to parse PROXY protocol addresses", e);
			return null;
		}
	}
}
