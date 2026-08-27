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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.ftpserver.DataConnectionConfiguration;
import org.apache.ftpserver.DataConnectionException;
import org.apache.ftpserver.ftplet.DataConnection;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.listener.Listener;
import org.apache.ftpserver.ssl.ClientAuth;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>Internal class, do not use directly.</strong>
 * <p>
 * We can get the FTP data connection using this class. It uses either PORT or PASV command.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class IODataConnectionFactory implements ServerDataConnectionFactory {

    private final Logger LOG = LoggerFactory.getLogger(IODataConnectionFactory.class);

    private FtpServerContext serverContext;

    private Socket dataSoc;

    ServerSocket servSoc;

    InetAddress address;

    int port = 0;

    long requestTime = 0L;

    /** When the transfer began waiting for the data connection; see {@link #logPassiveAccept()}. */
    private long acceptStartTime = 0L;

    boolean passive = false;

    boolean secure = false;

    private boolean isZip = false;

    private PassiveConnectionService.Reservation passiveReservation;
    private PassiveConnectionService passiveConnectionService;

    InetAddress serverControlAddress;

    FtpIoSession session;

    /**
     * Create a IODataConnectionFactory instance
     *
     * @param serverContext The FTP server context
     * @param session The FTP session
     */
    public IODataConnectionFactory(final FtpServerContext serverContext, final FtpIoSession session) {
        this.session = session;
        this.serverContext = serverContext;

        if ((session != null) && (session.getListener() != null) &&
            session.getListener().getDataConnectionConfiguration().isImplicitSsl()) {
            secure = true;
        }
    }

    /**
     * Close data socket. This method must be idempotent as we might call it multiple times during disconnect.
     */
    public synchronized void closeDataConnection() {
        // close client socket if any
        if (dataSoc != null) {
            try {
                dataSoc.close();
            } catch (Exception ex) {
                LOG.warn("FtpDataConnection.closeDataSocket()", ex);
            }

            dataSoc = null;
        }

        DataConnectionConfiguration dcc = null;
        if (session != null && session.getListener() != null) {
            dcc = session.getListener().getDataConnectionConfiguration();
        }

        // close server socket if any
        if (servSoc != null) {
            try {
                servSoc.close();
            } catch (Exception ex) {
                LOG.warn("FtpDataConnection.closeDataSocket()", ex);
            }
            servSoc = null;
        }

        // Always clean pending passive reservation/port even if no server socket is present.
        if (dcc != null && passive) {
            if (isMultiplexEnabled(dcc)) {
                if (passiveConnectionService != null && passiveReservation != null) {
                    passiveConnectionService.cancel(passiveReservation);
                }
            } else if (port > 0) {
                dcc.releasePassivePort(port);
            }
        }

        passiveReservation = null;
        passiveConnectionService = null;
        passive = false;
        port = 0;

        // reset request time
        requestTime = 0L;
    }

    /**
     * Port command.
     *
     * {@inheritDoc}
     */
    public synchronized void initActiveDataConnection(final InetSocketAddress address) {
        // close old sockets if any
        closeDataConnection();

        // set variables
        passive = false;
        this.address = address.getAddress();
        port = address.getPort();
        requestTime = System.currentTimeMillis();
    }

    private SslConfiguration getSslConfiguration() {
        DataConnectionConfiguration dataCfg = session.getListener().getDataConnectionConfiguration();

        SslConfiguration configuration = dataCfg.getSslConfiguration();

        // fall back if no configuration has been provided on the data connection config
        if (configuration == null) {
            configuration = session.getListener().getSslConfiguration();
        }

        return configuration;
    }

    /**
     * Initiate a data connection in passive mode (server listening).
     *
     * {@inheritDoc}
     */
    public synchronized InetSocketAddress initPassiveDataConnection() throws DataConnectionException {
        LOG.debug("Initiating passive data connection");
        // close old sockets if any
        closeDataConnection();

        try {
            DataConnectionConfiguration dataCfg = session.getListener().getDataConnectionConfiguration();

            String passiveAddress = dataCfg.getPassiveAddress();

            if (passiveAddress == null) {
                address = serverControlAddress;
            } else {
                address = resolveAddress(dataCfg.getPassiveAddress());
            }

            if (isMultiplexEnabled(dataCfg)) {
                Listener listener = session.getListener();
                PassiveConnectionService service = listener.getPassiveConnectionService();
                if (service == null) {
                    throw new DataConnectionException(
                            "Passive port multiplexing is enabled but no service is available");
                }
                passiveConnectionService = service;
                InetAddress clientAddress = ((InetSocketAddress) session.getRemoteAddress()).getAddress();
                passiveReservation = passiveConnectionService.register(clientAddress);
                port = passiveReservation.getPort();
                passive = true;
                requestTime = System.currentTimeMillis();

                logPassiveGrant("reserved");

                return new InetSocketAddress(address, port);
            }

            // get the passive port
            int passivePort = dataCfg.requestPassivePort();

            if (passivePort == -1) {
                servSoc = null;
                port = 0;
                throw new DataConnectionException("Cannot find an available passive port.");
            }
            port = passivePort;

            // open passive server socket and get parameters
            if (secure) {
                LOG.debug("Opening SSL passive data connection on address \"{}\" and port {}", address, passivePort);
                SslConfiguration ssl = getSslConfiguration();

                if (ssl == null) {
                    throw new DataConnectionException("Data connection SSL required but not configured.");
                }

                // this method does not actually create the SSL socket, due to a JVM bug
                // (https://issues.apache.org/jira/browse/FTPSERVER-241).
                // Instead, it creates a regular
                // ServerSocket that will be wrapped as a SSL socket in createDataSocket()
                servSoc = new ServerSocket(passivePort, 0, address);
                LOG.debug("SSL Passive data connection created on address \"{}\" and port {}", address, passivePort);
            } else {
                LOG.debug("Opening passive data connection on address \"{}\" and port {}", address, passivePort);
                servSoc = new ServerSocket(passivePort, 0, address);
                LOG.debug("Passive data connection created on address \"{}\" and port {}", address, passivePort);
            }

            port = servSoc.getLocalPort();
            servSoc.setSoTimeout(dataCfg.getIdleTime() * 1000);

            // set different state variables
            passive = true;
            requestTime = System.currentTimeMillis();

            logPassiveGrant("bound");

            return new InetSocketAddress(address, port);
        } catch (Exception ex) {
            closeDataConnection();
            throw new DataConnectionException("Failed to initate passive data connection: " + ex.getMessage(), ex);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.ftpserver.FtpDataConnectionFactory2#getInetAddress()
     */
    public InetAddress getInetAddress() {
        return address;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.ftpserver.FtpDataConnectionFactory2#getPort()
     */
    public int getPort() {
        return port;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.ftpserver.FtpDataConnectionFactory2#openConnection()
     */
    public DataConnection openConnection() throws Exception {
        return new IODataConnection(createDataSocket(), session, this);
    }

    /**
     * Get the data socket. In case of error returns null.
     */
    private synchronized Socket createDataSocket() throws Exception {
        // get socket depending on the selection
        dataSoc = null;
        DataConnectionConfiguration dataConfig = session.getListener().getDataConnectionConfiguration();

        try {
            if (!passive) {
                if (secure) {
                    LOG.debug("Opening secure active data connection");
                    SslConfiguration ssl = getSslConfiguration();

                    if (ssl == null) {
                        throw new FtpException("Data connection SSL not configured");
                    }

                    // get socket factory
                    SSLSocketFactory socFactory = ssl.getSocketFactory();

                    // create socket
                    SSLSocket ssoc = (SSLSocket) socFactory.createSocket();
                    ssoc.setUseClientMode(false);

                    // initialize socket
                    if (ssl.getEnabledCipherSuites() != null) {
                        ssoc.setEnabledCipherSuites(ssl.getEnabledCipherSuites());
                    }

                    if (ssl.getEnabledProtocols() != null) {
                        ssoc.setEnabledProtocols(ssl.getEnabledProtocols());
                    }

                    dataSoc = ssoc;
                } else {
                    LOG.debug("Opening active data connection");
                    dataSoc = new Socket();
                }

                dataSoc.setReuseAddress(true);

                InetAddress localAddr = resolveAddress(dataConfig.getActiveLocalAddress());

                // if no local address has been configured, make sure we use the same as the client connects from
                if (localAddr == null) {
                    localAddr = ((InetSocketAddress) session.getLocalAddress()).getAddress();
                }

                SocketAddress localSocketAddress = new InetSocketAddress(localAddr, dataConfig.getActiveLocalPort());

                LOG.debug("Binding active data connection to {}", localSocketAddress);
                dataSoc.bind(localSocketAddress);

                dataSoc.connect(new InetSocketAddress(address, port));
            } else {
                Socket acceptedSocket;
                acceptStartTime = System.currentTimeMillis();

                if (isMultiplexEnabled(session.getListener().getDataConnectionConfiguration())) {
                    if (passiveReservation == null || passiveConnectionService == null) {
                        throw new FtpException("Passive port reservation missing");
                    }
                    int timeout = dataConfig.getIdleTime() * 1000;
                    acceptedSocket = passiveReservation.await(timeout);
                    if (acceptedSocket == null) {
                        throw new FtpException("Passive data connection timed out");
                    }
                } else {
                    if (secure) {
                        LOG.debug("Opening secure passive data connection");
                        // keep wrapping immediately after accept due to JVM bug (FTPSERVER-241)
                        SslConfiguration ssl = getSslConfiguration();

                        if (ssl == null) {
                            throw new FtpException("Data connection SSL not configured");
                        }

                        SSLSocketFactory ssocketFactory = ssl.getSocketFactory();

                        Socket serverSocket = servSoc.accept();

                        // Best-effort PROXY header detection before TLS wrapping
                        if (session.getListener().isProxyProtocol()) {
                            serverSocket = new ProxyAwareSocket(serverSocket);
                        }

                        SSLSocket sslSocket = (SSLSocket) ssocketFactory.createSocket(serverSocket,
                            serverSocket.getInetAddress().getHostAddress(), serverSocket.getPort(), true);
                        sslSocket.setUseClientMode(false);

                        if (ssl.getClientAuth() == ClientAuth.NEED) {
                            sslSocket.setNeedClientAuth(true);
                        } else if (ssl.getClientAuth() == ClientAuth.WANT) {
                            sslSocket.setWantClientAuth(true);
                        }

                        if (ssl.getEnabledCipherSuites() != null) {
                            sslSocket.setEnabledCipherSuites(ssl.getEnabledCipherSuites());
                        }

                        if (ssl.getEnabledProtocols() != null) {
                            sslSocket.setEnabledProtocols(ssl.getEnabledProtocols());
                        }

                        dataSoc = sslSocket;
                        acceptedSocket = dataSoc;
                    } else {
                        LOG.debug("Opening passive data connection");
                        Socket rawSocket = servSoc.accept();

                        // Best-effort PROXY header detection
                        if (session.getListener().isProxyProtocol()) {
                            rawSocket = new ProxyAwareSocket(rawSocket);
                        }

                        acceptedSocket = rawSocket;
                    }
                }

                if (secure && !isMultiplexEnabled(session.getListener().getDataConnectionConfiguration())) {
                    // non-multiplex secure path already wrapped above
                } else if (secure) {
                    // Wrap the accepted socket for TLS even when multiplexing is enabled.
                    SslConfiguration ssl = getSslConfiguration();

                    if (ssl == null) {
                        throw new FtpException("Data connection SSL not configured");
                    }

                    SSLSocketFactory ssocketFactory = ssl.getSocketFactory();
                    SSLSocket sslSocket = (SSLSocket) ssocketFactory.createSocket(
                            acceptedSocket,
                            acceptedSocket.getInetAddress().getHostAddress(),
                            acceptedSocket.getPort(), true);
                    sslSocket.setUseClientMode(false);

                    if (ssl.getClientAuth() == ClientAuth.NEED) {
                        sslSocket.setNeedClientAuth(true);
                    } else if (ssl.getClientAuth() == ClientAuth.WANT) {
                        sslSocket.setWantClientAuth(true);
                    }

                    if (ssl.getEnabledCipherSuites() != null) {
                        sslSocket.setEnabledCipherSuites(ssl.getEnabledCipherSuites());
                    }

                    if (ssl.getEnabledProtocols() != null) {
                        sslSocket.setEnabledProtocols(ssl.getEnabledProtocols());
                    }

                    dataSoc = sslSocket;
                } else {
                    dataSoc = acceptedSocket;
                }

                logPassiveAccept();

                if (dataConfig.isPassiveIpCheck() && !session.getListener().isProxyProtocol()) {
                    // When proxy protocol is active, the socket address doesn't match
                    // the PROXY-provided client address — skip the check.
                    InetAddress remoteAddress = ((InetSocketAddress) session.getRemoteAddress()).getAddress();
                    InetAddress dataSocketAddress = dataSoc.getInetAddress();

                    if (!isSameAddressForPassiveIpCheck(remoteAddress, dataSocketAddress)) {
                        LOG.warn("Passive IP Check failed. Closing data connection from " + dataSocketAddress +
                            " as it does not match the expected address " + remoteAddress);
                        closeDataConnection();
                        return null;
                    }
                }

                DataConnectionConfiguration dataCfg = session.getListener().getDataConnectionConfiguration();

                dataSoc.setSoTimeout(dataCfg.getIdleTime() * 1000);
                LOG.debug("Passive data connection opened");
            }
        } catch (Exception ex) {
            // Capture before closeDataConnection(), which resets port/requestTime/passive.
            int failedPort = port;
            boolean wasPassive = passive;
            long waitedMillis = requestTime > 0 ? System.currentTimeMillis() - requestTime : -1;

            closeDataConnection();

            LOG.warn("FtpDataConnection.getDataSocket() failed: session={} user={} passive={} "
                            + "passivePort={} waitedMs={}",
                    session.getSessionId(), userName(), wasPassive, failedPort, waitedMillis, ex);
            throw ex;
        }

        dataSoc.setSoTimeout(dataConfig.getIdleTime() * 1000);

        // Make sure we initiate the SSL handshake, or we'll
        // get an error if we turn out not to send any data
        // e.g. during the listing of an empty directory
        if (dataSoc instanceof SSLSocket) {
            ((SSLSocket) dataSoc).startHandshake();
        }

        return dataSoc;
    }

    /*
     * (non-Javadoc) Returns an InetAddress object from a hostname or IP address.
     */
    private InetAddress resolveAddress(String host) throws DataConnectionException {
        if (host == null) {
            return null;
        } else {
            try {
                return InetAddress.getByName(host);
            } catch (UnknownHostException ex) {
                throw new DataConnectionException("Failed to resolve address", ex);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Set the security protocol.
     * {@inheritDoc}
     */
    public void setSecure(final boolean secure) {
        this.secure = secure;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isZipMode() {
        return isZip;
    }

    private String userName() {
        return session != null && session.getUser() != null ? session.getUser().getName() : "<none>";
    }

    private InetAddress controlAddress() {
        if (session == null) {
            return null;
        }
        SocketAddress remote = session.getRemoteAddress();
        return remote instanceof InetSocketAddress ? ((InetSocketAddress) remote).getAddress() : null;
    }

    /**
     * Records which passive port was handed to which session, at the moment it is advertised.
     *
     * <p>Logged at INFO because passive port assignment cannot be reconstructed after the fact:
     * the 227 reply is written asynchronously, so the reply seen next to a command in the log is
     * not necessarily the reply to that command.</p>
     *
     * @param how {@code bound} for a dedicated listener, {@code reserved} when multiplexing
     */
    private void logPassiveGrant(String how) {
        InetAddress control = controlAddress();
        LOG.info("Passive port {}: session={} user={} passivePort={} control={}",
                how,
                session != null ? session.getSessionId() : null,
                userName(),
                port,
                control != null ? control.getHostAddress() : "<unknown>");
    }

    /**
     * Records a passive data connection at accept time: which passive port it landed on, where it
     * actually came from, and how long the transfer waited for it.
     *
     * <p>The source address and port are the point of this. Without them there is no way to tell a
     * correctly matched data connection from one opened by a different client that happened to
     * reach this listener — the blind spot behind the mislabelled uploads investigated in
     * varanus-pos-ftp#38, where a file's contents did not match its name and nothing in the logs
     * could say why.</p>
     */
    private void logPassiveAccept() {
        if (dataSoc == null) {
            return;
        }

        InetAddress dataAddress = dataSoc.getInetAddress();
        InetAddress control = controlAddress();
        long now = System.currentTimeMillis();
        long sincePasvMillis = requestTime > 0 ? now - requestTime : -1;
        // Time actually spent blocked in accept(). A value near zero means the connection was
        // already sitting in the listener's backlog before the transfer command was processed —
        // i.e. the client connected ahead of the command the server is currently running, which
        // is what a name/content mismatch would look like from this side.
        long acceptWaitMillis = acceptStartTime > 0 ? now - acceptStartTime : -1;

        LOG.info("Passive data connection accepted: session={} user={} passivePort={} from={}:{} "
                        + "control={} sincePasvMs={} acceptWaitMs={}",
                session != null ? session.getSessionId() : null,
                userName(),
                port,
                dataAddress != null ? dataAddress.getHostAddress() : "<unknown>",
                dataSoc.getPort(),
                control != null ? control.getHostAddress() : "<unknown>",
                sincePasvMillis,
                acceptWaitMillis);

        // Deliberately independent of passiveIpCheck, which defaults to false and is disabled in
        // production: with that guard off we would otherwise keep no record at all of a data
        // connection arriving from somewhere other than the session that asked for it.
        if (dataAddress != null && control != null && !dataAddress.equals(control)) {
            boolean checkEnabled = session.getListener().getDataConnectionConfiguration()
                    .isPassiveIpCheck();
            LOG.warn("Passive data connection SOURCE MISMATCH: session={} user={} passivePort={} "
                            + "arrived from {} but this session's control connection is {} "
                            + "(passiveIpCheck={} - connection {})",
                    session.getSessionId(),
                    userName(),
                    port,
                    dataAddress.getHostAddress(),
                    control.getHostAddress(),
                    checkEnabled,
                    checkEnabled ? "will be rejected" : "IS BEING USED");
        }
    }

    private boolean isMultiplexEnabled(DataConnectionConfiguration cfg) {
        if (cfg instanceof DefaultDataConnectionConfiguration) {
            return ((DefaultDataConnectionConfiguration) cfg).isMultiplexPassivePorts();
        }
        return false;
    }

    /**
     * Compare client addresses for passive IP checks while handling IPv4-mapped IPv6.
     * This avoids rejecting valid connections where control and data sockets use
     * different address-family representations of the same endpoint.
     */
    static boolean isSameAddressForPassiveIpCheck(InetAddress expected, InetAddress actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] expectedBytes = normalizeMappedIpv4(expected.getAddress());
        byte[] actualBytes = normalizeMappedIpv4(actual.getAddress());
        return java.util.Arrays.equals(expectedBytes, actualBytes);
    }

    private static byte[] normalizeMappedIpv4(byte[] raw) {
        if (isIpv4MappedIpv6(raw)) {
            byte[] ipv4 = new byte[4];
            System.arraycopy(raw, 12, ipv4, 0, 4);
            return ipv4;
        }
        return raw;
    }

    private static boolean isIpv4MappedIpv6(byte[] raw) {
        if (raw == null || raw.length != 16) {
            return false;
        }

        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
    }

    /**
     * Set zip mode.
     * {@inheritDoc}
     */
    public void setZipMode(final boolean zip) {
        isZip = zip;
    }

    /**
     * Check the data connection idle status.
     * {@inheritDoc}
     */
    public synchronized boolean isTimeout(final long currTime) {
        // data connection not requested - not a timeout
        if (requestTime == 0L) {
            return false;
        }

        // data connection active - not a timeout
        if (dataSoc != null) {
            return false;
        }

        // no idle time limit - not a timeout
        int maxIdleTime = session.getListener().getDataConnectionConfiguration().getIdleTime() * 1000;
        if (maxIdleTime == 0) {
            return false;
        }

        // idle time is within limit - not a timeout
        return ((currTime - requestTime) >= maxIdleTime);
    }

    /**
     * Dispose data connection - close all the sockets.
     */
    public void dispose() {
        closeDataConnection();
    }

    /**
     * Sets the server's control address.
     * {@inheritDoc}
     */
    public void setServerControlAddress(final InetAddress serverControlAddress) {
        this.serverControlAddress = serverControlAddress;
    }
}
