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
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ftpserver.DataConnectionConfigurationFactory;

/**
 * Scenarios around a session that asks for a passive port while it still holds one it has not
 * used, run with and without {@link DataConnectionConfigurationFactory#setPassiveReuseUnusedListener}.
 * <p>
 * The control connections are raw sockets, so that commands can be sent without waiting for their
 * replies, as some clients do. Everything is ordered explicitly: no scenario depends on timing.
 */
public abstract class PassiveListenerTestTemplate extends ClientTestTemplate {

    /** Two passive ports: when a session gives one back, the next session to ask gets it. */
    private static final String PASSIVE_PORTS = "50071-50072";

    private static final Pattern PASV_PORT = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");

    private static final Pattern EPSV_PORT = Pattern.compile("\\(\\|\\|\\|(\\d+)\\|\\)");

    /** Whether the server re-advertises an unused passive listener. */
    protected abstract boolean reuse();

    @Override
    protected DataConnectionConfigurationFactory createDataConnectionConfigurationFactory() {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        factory.setMultiplexPassivePorts(false);
        factory.setPassivePorts(PASSIVE_PORTS);
        factory.setPassiveReuseUnusedListener(reuse());
        // a STOR whose data connection never comes gives up after 2 s
        factory.setIdleTime(2);
        return factory;
    }

    @Override
    protected boolean isConnectClient() {
        return false;
    }

    /** A raw control connection. */
    protected final class Control implements Closeable {
        private final Socket socket = new Socket();
        private final BufferedReader in;
        private final Writer out;

        Control() throws IOException {
            socket.connect(new InetSocketAddress("127.0.0.1", getListenerPort()), 10000);
            socket.setSoTimeout(15000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
            assertEquals(220, code(readReply()));
            assertEquals(331, command("USER " + ADMIN_USERNAME));
            assertEquals(230, command("PASS " + ADMIN_PASSWORD));
        }

        void send(String command) throws IOException {
            out.write(command + "\r\n");
            out.flush();
        }

        int command(String command) throws IOException {
            send(command);
            return code(readReply());
        }

        /** The last line of the next reply, multi-line replies included. */
        String readReply() throws IOException {
            String line = in.readLine();
            assertNotNull("connection closed while waiting for a reply", line);
            if (line.length() > 3 && line.charAt(3) == '-') {
                String end = line.substring(0, 3) + " ";
                while (!line.startsWith(end)) {
                    line = in.readLine();
                    assertNotNull("connection closed in a multi-line reply", line);
                }
            }
            return line;
        }

        /** Reads a PASV (227) or EPSV (229) reply and returns the port it advertises. */
        int readPassivePort() throws IOException {
            String reply = readReply();
            Matcher pasv = PASV_PORT.matcher(reply);
            if (reply.startsWith("227") && pasv.find()) {
                return Integer.parseInt(pasv.group(5)) * 256 + Integer.parseInt(pasv.group(6));
            }
            Matcher epsv = EPSV_PORT.matcher(reply);
            if (reply.startsWith("229") && epsv.find()) {
                return Integer.parseInt(epsv.group(1));
            }
            fail("not a passive reply: " + reply);
            return -1;
        }

        /** QUIT, so that the server closes the session (and gives its port back) promptly. */
        @Override
        public void close() throws IOException {
            try {
                if (!socket.isClosed()) {
                    send("QUIT");
                    readReply();
                }
            } catch (IOException | AssertionError ignored) {
                // already gone
            } finally {
                socket.close();
            }
        }
    }

    /**
     * Waits until the server has closed every session. It gives a session's passive port back
     * when it closes the session, which happens after the client has gone.
     */
    protected void awaitNoSessions() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!server.getListener("default").getActiveSessions().isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("sessions still open after 5 s", server.getListener("default").getActiveSessions().isEmpty());
    }

    @Override
    protected void tearDown() throws Exception {
        if (server != null && !server.isStopped()) {
            awaitNoSessions();
        }
        super.tearDown();
    }

    protected static int code(String reply) {
        return Integer.parseInt(reply.substring(0, 3));
    }

    /** Opens a data connection to the given port and sends <code>data</code> on it, then EOF. */
    protected static Socket connectAndSend(int port, String data) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
        OutputStream out = socket.getOutputStream();
        out.write(data.getBytes(StandardCharsets.UTF_8));
        out.flush();
        socket.shutdownOutput();
        return socket;
    }

    /** STOR on <code>control</code>, returning the reply that ends it. */
    protected static int store(Control control, String name) throws IOException {
        control.send("STOR " + name);
        String first = control.readReply();
        if (code(first) != 150) {
            return code(first);
        }
        return code(control.readReply());
    }

    protected static String stored(String name) throws IOException {
        File file = new File(ROOT_DIR, name);
        return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
    }

    /**
     * Two PASVs sent together, without waiting: with reuse both replies advertise the same
     * listener. (Without reuse the second binds a new one, on whichever free port the pool picks,
     * which may happen to be the same number.)
     */
    public void testPipelinedPasvAdvertisesOneListener() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            try (Control a = new Control()) {
                a.send("PASV");
                a.send("PASV");
                int first = a.readPassivePort();
                int second = a.readPassivePort();
                if (reuse()) {
                    assertEquals("round " + round + ": the second PASV must re-advertise the first listener",
                            first, second);
                }
            }
            awaitNoSessions();
        }
    }

    /** EPSV and PASV share the listener in either order. */
    public void testEpsvAndPasvShareTheListener() throws Exception {
        try (Control a = new Control()) {
            a.send("EPSV");
            int epsv = a.readPassivePort();
            a.send("PASV");
            int pasv = a.readPassivePort();
            a.send("EPSV");
            int epsvAgain = a.readPassivePort();
            if (reuse()) {
                assertEquals(epsv, pasv);
                assertEquals(pasv, epsvAgain);
            }
        }
    }

    /**
     * The client connects to the port from the first reply, before the server sees its second
     * PASV. With reuse that connection is the session's next transfer; without, the second PASV
     * closes the listener under it and the STOR finds no data connection.
     */
    public void testAConnectionMadeAfterTheFirstReplyFeedsTheSessionsStor() throws Exception {
        try (Control a = new Control()) {
            a.send("PASV");
            int first = a.readPassivePort();
            try (Socket data = connectAndSend(first, "A-DATA")) {
                a.send("PASV");
                a.readPassivePort();
                int result = store(a, "a.txt");
                if (reuse()) {
                    assertEquals(226, result);
                    assertEquals("A-DATA", stored("a.txt"));
                } else {
                    assertEquals("the connection to the replaced listener is lost", 425, result);
                }
            }
        }
    }

    /**
     * The sequence that crosses data between sessions: A sends PASV twice and connects to the
     * port of the first reply; meanwhile B asks for a port. Without reuse A has already given
     * that port back and B gets it, so B's STOR takes A's data connection and stores A's data as
     * its own file. With reuse A keeps its port and each file holds its own session's data.
     */
    public void testAnUnusedPortIsNotHandedToAnotherSession() throws Exception {
        if (reuse()) {
            // The pool picks ports at random, so without reuse a round only exposes the defect
            // when it moves A to the other port - half the time. Ten rounds that must all hold
            // make a server without reuse fail this test with probability 1 - 2^-10.
            for (int round = 0; round < ROUNDS; round++) {
                assertTrue(crossingScenario());
                awaitNoSessions();
                cleanStored("a.txt", "b.txt");
            }
            return;
        }
        // Without reuse, retry until the pool has moved A to the other port: that is the scenario.
        for (int attempt = 0; attempt < 50; attempt++) {
            if (crossingScenario()) {
                return;
            }
            awaitNoSessions();
        }
        fail("the pool never moved A to the other port in 50 attempts");
    }

    /** Rounds for scenarios whose outcome without reuse depends on the pool's random choice. */
    protected static final int ROUNDS = 10;

    protected static void cleanStored(String... names) {
        for (String name : names) {
            new File(ROOT_DIR, name).delete();
        }
    }

    /** Returns false when the pool happened to give A the same port twice without reuse. */
    private boolean crossingScenario() throws Exception {
        try (Control a = new Control(); Control b = new Control()) {
            a.send("PASV");
            a.send("PASV");
            int aFirst = a.readPassivePort();
            int aSecond = a.readPassivePort();
            if (!reuse() && aSecond == aFirst) {
                return false;
            }

            b.send("PASV");
            int bPort = b.readPassivePort();

            try (Socket aData = connectAndSend(aFirst, "A-DATA");
                    Socket bData = connectAndSend(bPort, "B-DATA")) {
                if (reuse()) {
                    assertEquals(aFirst, aSecond);
                    assertTrue("B must not get the port A was given", bPort != aFirst);

                    assertEquals(226, store(b, "b.txt"));
                    assertEquals(226, store(a, "a.txt"));
                    assertEquals("B-DATA", stored("b.txt"));
                    assertEquals("A-DATA", stored("a.txt"));
                } else {
                    assertEquals("B gets the port A gave back", aFirst, bPort);

                    assertEquals(226, store(b, "b.txt"));
                    assertEquals("B's file holds A's data: the defect reuse prevents", "A-DATA", stored("b.txt"));
                    assertEquals("A's STOR waits on a listener nobody connects to", 425, store(a, "a.txt"));
                }
            }
        }
        return true;
    }

    /** Re-advertising holds no extra port, and every port is free again once the sessions end. */
    public void testNoPortIsHeldBeyondTheSession() throws Exception {
        try (Control a = new Control(); Control b = new Control()) {
            a.send("PASV");
            a.send("PASV");
            a.send("PASV");
            a.readPassivePort();
            a.readPassivePort();
            a.readPassivePort();
            b.send("PASV");
            b.readPassivePort();
            assertEquals(221, a.command("QUIT"));
            assertEquals(221, b.command("QUIT"));
        }
        awaitNoSessions();
        try (Control c = new Control(); Control d = new Control(); Control e = new Control()) {
            c.send("PASV");
            c.readPassivePort();
            d.send("PASV");
            d.readPassivePort();
            assertEquals("the two ports are in use, by c and d", 425, e.command("PASV"));
        }
    }

    /** After a transfer the listener is gone: the next PASV opens a fresh one and transfers work. */
    public void testTransfersFollowEachOther() throws Exception {
        try (Control a = new Control()) {
            for (int i = 0; i < 3; i++) {
                a.send("PASV");
                int port = a.readPassivePort();
                try (Socket data = connectAndSend(port, "DATA-" + i)) {
                    assertEquals(226, store(a, "seq-" + i + ".txt"));
                }
                assertEquals("DATA-" + i, stored("seq-" + i + ".txt"));
            }
        }
    }

    /** ABOR gives the port back: the other session can then have both. */
    public void testAborReleasesTheListener() throws Exception {
        try (Control a = new Control(); Control b = new Control(); Control c = new Control()) {
            a.send("PASV");
            a.readPassivePort();
            a.send("ABOR");
            a.readReply();
            b.send("PASV");
            b.readPassivePort();
            c.send("PASV");
            c.readPassivePort();
        }
    }

    /** PORT replaces a passive listener and gives its port back. */
    public void testPortReplacesTheListener() throws Exception {
        try (Control a = new Control(); Control b = new Control(); Control c = new Control()) {
            a.send("PASV");
            a.readPassivePort();
            assertEquals(200, a.command("PORT 127,0,0,1,195,80"));
            b.send("PASV");
            b.readPassivePort();
            c.send("PASV");
            c.readPassivePort();
        }
    }
}
