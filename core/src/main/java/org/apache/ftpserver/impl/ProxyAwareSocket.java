package org.apache.ftpserver.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A delegating wrapper around a {@link Socket} that performs best-effort
 * PROXY Protocol v2 detection on construction.
 *
 * <p>On construction, the socket's input stream is wrapped in a
 * {@link PushbackInputStream}. The wrapper reads the first byte and, if it
 * matches the start of the PROXY Protocol v2 signature ({@code 0x0D}),
 * attempts to read and parse a full v2 header. If parsing succeeds the
 * header bytes are consumed; if it fails at any point (signature mismatch,
 * wrong version, short read, EOF, I/O error) <strong>all</strong> bytes
 * read so far are pushed back so that subsequent consumers see the
 * original byte stream unmodified.</p>
 *
 * <p>When a valid PROXY header with a PROXY command is parsed,
 * {@link #getInetAddress()} and {@link #getPort()} return the source
 * address/port from the header. For LOCAL commands or when no header is
 * detected they delegate to the underlying socket.</p>
 */
public class ProxyAwareSocket extends Socket {

	private static final Logger LOG = LoggerFactory.getLogger(ProxyAwareSocket.class);

	private static final byte[] V2_SIGNATURE = {
			0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A,
			0x51, 0x55, 0x49, 0x54, 0x0A
	};

	private static final int SIGNATURE_LENGTH = 12;
	private static final int PUSHBACK_BUFFER_SIZE = 256;

	private final Socket delegate;
	private final PushbackInputStream pushbackIn;
	private ProxyProtocolResult proxyResult;

	/**
	 * Wraps the given socket, attempting best-effort PROXY Protocol v2
	 * detection. The constructor reads from the socket; on return the
	 * stream is positioned either after the consumed header or at the
	 * original position (all bytes pushed back).
	 *
	 * @param socket the accepted socket to wrap
	 * @throws IOException if obtaining the socket's input stream fails
	 */
	public ProxyAwareSocket(Socket socket) throws IOException {
		this.delegate = socket;
		this.pushbackIn = new PushbackInputStream(socket.getInputStream(), PUSHBACK_BUFFER_SIZE);
		detectProxy();
	}

	// ------------------------------------------------------------------
	// PROXY detection
	// ------------------------------------------------------------------

	private void detectProxy() {
		// Accumulator for all bytes read so we can push them back on failure.
		// Maximum we might read: 12 (sig) + 4 (hdr) + 216 (max addr block) = 232
		byte[] buf = new byte[PUSHBACK_BUFFER_SIZE];
		int totalRead = 0;

		try {
			// 1. Read first byte
			int firstByte = pushbackIn.read();
			if (firstByte == -1) {
				// EOF immediately - nothing to push back
				return;
			}
			buf[totalRead++] = (byte) firstByte;

			if ((byte) firstByte != V2_SIGNATURE[0]) {
				// Not the start of a PROXY header - push back and return
				pushbackIn.unread(buf, 0, totalRead);
				return;
			}

			// 2. Read remaining 11 signature bytes
			int sigRemaining = SIGNATURE_LENGTH - 1;
			int sigRead = readAccumulate(pushbackIn, buf, totalRead, sigRemaining);
			totalRead += sigRead;

			if (sigRead < sigRemaining) {
				// Short read (EOF or not enough data) - push back everything
				pushbackIn.unread(buf, 0, totalRead);
				return;
			}

			// 3. Verify full signature
			if (!matchesSignature(buf)) {
				pushbackIn.unread(buf, 0, totalRead);
				return;
			}

			// 4. Read 4-byte header (ver/cmd, fam/proto, length)
			int hdrRead = readAccumulate(pushbackIn, buf, totalRead, 4);
			totalRead += hdrRead;

			if (hdrRead < 4) {
				pushbackIn.unread(buf, 0, totalRead);
				return;
			}

			int verCmd = buf[SIGNATURE_LENGTH] & 0xFF;
			int version = (verCmd >> 4) & 0x0F;

			if (version != 2) {
				LOG.debug("PROXY protocol: signature matched but version {} (expected 2)", version);
				pushbackIn.unread(buf, 0, totalRead);
				return;
			}

			int command = verCmd & 0x0F;
			int famProto = buf[SIGNATURE_LENGTH + 1] & 0xFF;
			int addrLen = ((buf[SIGNATURE_LENGTH + 2] & 0xFF) << 8) | (buf[SIGNATURE_LENGTH + 3] & 0xFF);

			// 5. Read address block
			if (addrLen > 0) {
				// Ensure we have room in our buffer
				if (totalRead + addrLen > buf.length) {
					// Address block too large for pushback buffer - push back and bail
					pushbackIn.unread(buf, 0, totalRead);
					return;
				}

				int addrRead = readAccumulate(pushbackIn, buf, totalRead, addrLen);
				totalRead += addrRead;

				if (addrRead < addrLen) {
					pushbackIn.unread(buf, 0, totalRead);
					return;
				}
			}

			// 6. Parse addresses from the address block
			byte[] addrBuf = new byte[addrLen];
			System.arraycopy(buf, SIGNATURE_LENGTH + 4, addrBuf, 0, addrLen);

			ProxyProtocolResult result = parseAddresses(command, famProto, addrBuf, addrLen);
			if (result != null) {
				this.proxyResult = result;
				LOG.debug("PROXY v2 detected: {}", result);
				// Header bytes are consumed - not pushed back
			} else {
				// parseAddresses returned null (unknown command, etc.) - push back
				pushbackIn.unread(buf, 0, totalRead);
			}

		} catch (IOException e) {
			LOG.debug("IOException during PROXY protocol detection, pushing back {} bytes", totalRead, e);
			try {
				if (totalRead > 0) {
					pushbackIn.unread(buf, 0, totalRead);
				}
			} catch (IOException pushbackError) {
				LOG.warn("Failed to push back bytes after PROXY detection error", pushbackError);
			}
		}
	}

	/**
	 * Reads up to {@code len} bytes into {@code buf} starting at {@code offset},
	 * accumulating across short reads. Returns the number of bytes actually read.
	 */
	private static int readAccumulate(PushbackInputStream in, byte[] buf, int offset, int len) throws IOException {
		int read = 0;
		while (read < len) {
			int n = in.read(buf, offset + read, len - read);
			if (n < 0) break;
			read += n;
		}
		return read;
	}

	private static boolean matchesSignature(byte[] buf) {
		for (int i = 0; i < SIGNATURE_LENGTH; i++) {
			if (buf[i] != V2_SIGNATURE[i]) return false;
		}
		return true;
	}

	/**
	 * Parses the addresses from a PROXY v2 header. Mirrors the logic in
	 * {@link ProxyProtocolParser} but kept here to avoid coupling.
	 */
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

	// ------------------------------------------------------------------
	// Public accessors
	// ------------------------------------------------------------------

	/**
	 * Returns the parsed PROXY Protocol result, or {@code null} if no
	 * valid header was detected.
	 */
	public ProxyProtocolResult getProxyResult() {
		return proxyResult;
	}

	/**
	 * Returns the actual (socket-level) remote address, regardless of
	 * whether a PROXY header was parsed. Useful for logging.
	 */
	public InetAddress getOriginalInetAddress() {
		return delegate.getInetAddress();
	}

	// ------------------------------------------------------------------
	// Socket method overrides
	// ------------------------------------------------------------------

	@Override
	public InputStream getInputStream() throws IOException {
		return pushbackIn;
	}

	@Override
	public InetAddress getInetAddress() {
		if (proxyResult != null && !proxyResult.local() && proxyResult.sourceAddress() != null) {
			return proxyResult.sourceAddress().getAddress();
		}
		return delegate.getInetAddress();
	}

	@Override
	public int getPort() {
		if (proxyResult != null && !proxyResult.local() && proxyResult.sourceAddress() != null) {
			return proxyResult.sourceAddress().getPort();
		}
		return delegate.getPort();
	}

	// --- Delegation of all other Socket methods ---

	@Override
	public OutputStream getOutputStream() throws IOException {
		return delegate.getOutputStream();
	}

	@Override
	public InetAddress getLocalAddress() {
		return delegate.getLocalAddress();
	}

	@Override
	public int getLocalPort() {
		return delegate.getLocalPort();
	}

	@Override
	public SocketAddress getRemoteSocketAddress() {
		if (proxyResult != null && !proxyResult.local() && proxyResult.sourceAddress() != null) {
			return proxyResult.sourceAddress();
		}
		return delegate.getRemoteSocketAddress();
	}

	@Override
	public SocketAddress getLocalSocketAddress() {
		return delegate.getLocalSocketAddress();
	}

	@Override
	public SocketChannel getChannel() {
		return delegate.getChannel();
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}

	@Override
	public boolean isClosed() {
		return delegate.isClosed();
	}

	@Override
	public boolean isConnected() {
		return delegate.isConnected();
	}

	@Override
	public boolean isBound() {
		return delegate.isBound();
	}

	@Override
	public boolean isInputShutdown() {
		return delegate.isInputShutdown();
	}

	@Override
	public boolean isOutputShutdown() {
		return delegate.isOutputShutdown();
	}

	@Override
	public void shutdownInput() throws IOException {
		delegate.shutdownInput();
	}

	@Override
	public void shutdownOutput() throws IOException {
		delegate.shutdownOutput();
	}

	@Override
	public void setSoTimeout(int timeout) throws SocketException {
		delegate.setSoTimeout(timeout);
	}

	@Override
	public int getSoTimeout() throws SocketException {
		return delegate.getSoTimeout();
	}

	@Override
	public void setSendBufferSize(int size) throws SocketException {
		delegate.setSendBufferSize(size);
	}

	@Override
	public int getSendBufferSize() throws SocketException {
		return delegate.getSendBufferSize();
	}

	@Override
	public void setReceiveBufferSize(int size) throws SocketException {
		delegate.setReceiveBufferSize(size);
	}

	@Override
	public int getReceiveBufferSize() throws SocketException {
		return delegate.getReceiveBufferSize();
	}

	@Override
	public void setKeepAlive(boolean on) throws SocketException {
		delegate.setKeepAlive(on);
	}

	@Override
	public boolean getKeepAlive() throws SocketException {
		return delegate.getKeepAlive();
	}

	@Override
	public void setTcpNoDelay(boolean on) throws SocketException {
		delegate.setTcpNoDelay(on);
	}

	@Override
	public boolean getTcpNoDelay() throws SocketException {
		return delegate.getTcpNoDelay();
	}

	@Override
	public void setSoLinger(boolean on, int linger) throws SocketException {
		delegate.setSoLinger(on, linger);
	}

	@Override
	public int getSoLinger() throws SocketException {
		return delegate.getSoLinger();
	}

	@Override
	public void setOOBInline(boolean on) throws SocketException {
		delegate.setOOBInline(on);
	}

	@Override
	public boolean getOOBInline() throws SocketException {
		return delegate.getOOBInline();
	}

	@Override
	public void setTrafficClass(int tc) throws SocketException {
		delegate.setTrafficClass(tc);
	}

	@Override
	public int getTrafficClass() throws SocketException {
		return delegate.getTrafficClass();
	}

	@Override
	public void setReuseAddress(boolean on) throws SocketException {
		delegate.setReuseAddress(on);
	}

	@Override
	public boolean getReuseAddress() throws SocketException {
		return delegate.getReuseAddress();
	}

	@Override
	public String toString() {
		if (proxyResult != null) {
			return "ProxyAwareSocket[proxy=" + proxyResult + ", delegate=" + delegate + "]";
		}
		return "ProxyAwareSocket[delegate=" + delegate + "]";
	}
}
