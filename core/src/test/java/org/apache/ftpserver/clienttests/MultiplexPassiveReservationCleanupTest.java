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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.test.TestUtil;

/**
 * Regression coverage for multiplexed passive reservation lifecycle.
 * Ensures stale reservations are cleaned up across repeated PASV and timeout paths.
 */
public class MultiplexPassiveReservationCleanupTest extends ClientTestTemplate {

    private int passivePort;

    @Override
    protected void setUp() throws Exception {
        passivePort = TestUtil.findFreePort(12000 + new Random().nextInt(20000));
        super.setUp();
    }

    @Override
    protected boolean isConnectClient() {
        return false;
    }

    @Override
    protected DataConnectionConfigurationFactory createDataConnectionConfigurationFactory() {
        DataConnectionConfigurationFactory dc = new DataConnectionConfigurationFactory();
        dc.setPassivePorts(String.valueOf(passivePort));
        dc.setPassiveIpCheck(true);
        dc.setMultiplexPassivePorts(true);
        dc.setIdleTime(1);
        return dc;
    }

    public void testRepeatedPasvReleasesPreviousReservation() throws Exception {
        try (ControlSession control = openControlSession()) {
            control.login(ADMIN_USERNAME, ADMIN_PASSWORD);

            for (int i = 0; i < 5; i++) {
                String pasvReply = control.command("PASV");
                assertTrue("PASV failed on iteration " + i + ": " + pasvReply, pasvReply.startsWith("227 "));
                assertEquals("Exactly one pending reservation should remain (latest PASV only)", 1,
                        server.getListener("default").getPassiveConnectionService().getActiveReservations());
            }
        }
    }

    public void testTimedOutTransferReleasesReservation() throws Exception {
        try (ControlSession control = openControlSession()) {
            control.login(ADMIN_USERNAME, ADMIN_PASSWORD);

            String pasvReply = control.command("PASV");
            assertTrue("PASV failed: " + pasvReply, pasvReply.startsWith("227 "));

            String listStartReply = control.command("LIST");
            assertTrue("LIST should start with 150 before data timeout: " + listStartReply,
                    listStartReply.startsWith("150 "));

            String timeoutReply = control.readReply();
            assertTrue("Expected LIST to fail with 425 after timeout: " + timeoutReply, timeoutReply.startsWith("425 "));

            waitForActiveReservations(0, 3000);

            String pasvAfterTimeout = control.command("PASV");
            assertTrue("PASV after timeout should succeed (stale reservation must be cleaned): " + pasvAfterTimeout,
                    pasvAfterTimeout.startsWith("227 "));
        }
    }

    private void waitForActiveReservations(int expected, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            int current = server.getListener("default").getPassiveConnectionService().getActiveReservations();
            if (current == expected) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals(expected, server.getListener("default").getPassiveConnectionService().getActiveReservations());
    }

    private ControlSession openControlSession() throws Exception {
        ControlSession session = new ControlSession(getListenerPort());
        String welcome = session.readReply();
        assertTrue("Unexpected welcome reply: " + welcome, welcome.startsWith("220 "));
        return session;
    }

    private static final class ControlSession implements Closeable {
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;

        private ControlSession(int port) throws Exception {
            socket = new Socket();
            socket.setSoTimeout(10000);
            socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private void login(String user, String pass) throws Exception {
            String userReply = command("USER " + user);
            if (!userReply.startsWith("331 ") && !userReply.startsWith("230 ")) {
                throw new IOException("Unexpected USER reply: " + userReply);
            }
            String passReply = command("PASS " + pass);
            if (!passReply.startsWith("230 ")) {
                throw new IOException("Unexpected PASS reply: " + passReply);
            }
        }

        private String command(String line) throws Exception {
            out.write(line);
            out.write("\r\n");
            out.flush();
            return readReply();
        }

        private String readReply() throws Exception {
            String reply = in.readLine();
            if (reply == null) {
                throw new IOException("Expected FTP reply line");
            }
            return reply;
        }

        public void close() throws IOException {
            try {
                out.write("QUIT\r\n");
                out.flush();
                in.readLine();
            } catch (Exception ignored) {
            }
            socket.close();
        }
    }
}
