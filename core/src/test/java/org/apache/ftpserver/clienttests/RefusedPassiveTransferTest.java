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
package org.apache.ftpserver.clienttests;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.IODataConnectionFactory;

/**
 * A transfer command after a PASV/EPSV that was refused for lack of a passive port.
 * <p>
 * The refused PASV offered no data connection, so a STOR that follows it is in the same position as
 * a STOR on a session that never sent PASV: it must be answered 503 ("PORT or PASV must be issued
 * first") without the server trying to connect anywhere. It used to be answered 150 and then 425:
 * the refused PASV left the server's own address set while resetting {@code passive} to false, so
 * the STOR took the active-mode path and connected to that address on port 0, a data connection no
 * PORT/EPRT ever requested.
 */
public class RefusedPassiveTransferTest extends ClientTestTemplate {

    /** The only passive port: whoever holds it makes every other PASV fail. */
    private static final int ONLY_PASSIVE_PORT = 23031;

    private FTPClient holder;

    @Override
    protected DataConnectionConfigurationFactory createDataConnectionConfigurationFactory() {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        factory.setMultiplexPassivePorts(false);
        factory.setPassivePorts(String.valueOf(ONLY_PASSIVE_PORT));
        return factory;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        client.setRemoteVerificationEnabled(false);
        assertTrue(client.login(ADMIN_USERNAME, ADMIN_PASSWORD));
    }

    @Override
    protected void tearDown() throws Exception {
        if (holder != null && holder.isConnected()) {
            holder.disconnect();
        }
        super.tearDown();
    }

    /** Another session takes the only passive port and keeps it. */
    private void holdTheOnlyPassivePort() throws Exception {
        holder = createFTPClient();
        holder.connect("localhost", getListenerPort());
        assertTrue(holder.login(ADMIN_USERNAME, ADMIN_PASSWORD));
        assertEquals(227, holder.sendCommand("PASV"));
    }

    /**
     * Ends the holder's session and waits until the server has released its port: that happens
     * when the server finishes closing the session, after the client has already disconnected.
     */
    private void releaseTheOnlyPassivePort() throws Exception {
        holder.logout();
        holder.disconnect();
        holder = null;

        long deadline = System.currentTimeMillis() + 5000;
        int reply;
        while ((reply = client.sendCommand("PASV")) != 227 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("the port was not released within 5 s", 227, reply);
    }

    public void testStorWithoutPasvIs503() throws Exception {
        assertEquals(503, client.sendCommand("STOR", "never-offered.txt"));
    }

    public void testStorAfterARefusedPasvIs503() throws Exception {
        holdTheOnlyPassivePort();

        assertEquals(425, client.sendCommand("PASV"));
        assertEquals("no data connection was offered", 503, client.sendCommand("STOR", "after-refused-pasv.txt"));
    }

    public void testStorAfterARefusedEpsvIs503() throws Exception {
        holdTheOnlyPassivePort();

        assertEquals(425, client.sendCommand("EPSV"));
        assertEquals("no data connection was offered", 503, client.sendCommand("STOR", "after-refused-epsv.txt"));
    }

    /** The refused PASV leaves no data address behind in the client's session on the server. */
    public void testARefusedPasvLeavesNoDataAddress() throws Exception {
        holdTheOnlyPassivePort();
        assertEquals(425, client.sendCommand("PASV"));
        assertEquals(200, client.sendCommand("NOOP"));

        FtpIoSession session = sessionOf(client);
        IODataConnectionFactory data = (IODataConnectionFactory) session.getDataConnection();
        assertNull("address left by the refused PASV: " + data.getInetAddress(), data.getInetAddress());
        assertEquals(0, data.getPort());
    }

    private FtpIoSession sessionOf(FTPClient ftpClient) {
        for (FtpIoSession session : server.getListener("default").getActiveSessions()) {
            if (((InetSocketAddress) session.getRemoteAddress()).getPort() == ftpClient.getLocalPort()) {
                return session;
            }
        }
        throw new AssertionError("no server session for client port " + ftpClient.getLocalPort());
    }

    public void testAPasvThatSucceedsLaterStillTransfers() throws Exception {
        holdTheOnlyPassivePort();
        assertEquals(425, client.sendCommand("PASV"));
        releaseTheOnlyPassivePort();

        client.enterLocalPassiveMode();
        assertTrue(client.storeFile("after-release.txt",
                new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8))));
    }
}
