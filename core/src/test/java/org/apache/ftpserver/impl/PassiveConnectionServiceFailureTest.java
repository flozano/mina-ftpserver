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

import org.apache.ftpserver.DataConnectionException;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for PassiveConnectionService network failure scenarios.
 */
public class PassiveConnectionServiceFailureTest {

    private PassiveConnectionService service;

    @After
    public void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    public void connectionTimeout() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        // Don't deliver any connection - should timeout
        long start = System.currentTimeMillis();
        Socket socket = res.await(500);
        long elapsed = System.currentTimeMillis() - start;

        assertNull("Should timeout with no connection", socket);
        assertTrue("Should wait close to timeout period", elapsed >= 450 && elapsed < 1000);
    }

    @Test
    public void wrongIPConnectionRejected() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        InetAddress wrongClient = InetAddress.getByName("127.0.0.2");

        PassiveConnectionService.Reservation res = service.register(client);

        // Deliver from wrong IP
        FakeSocket wrongSocket = new FakeSocket(wrongClient);
        wrongSocket.setCloseTracking(true);
        service.deliverAccepted(port, wrongSocket);

        Socket delivered = res.await(200);
        assertNull("Should not deliver socket from wrong IP", delivered);

        // Verify socket was closed
        assertEquals("Wrong IP socket should be closed", 1, wrongSocket.closeCalls);
        assertEquals("Should count as rejection", 1, service.getRejectedConnections());
    }

    @Test
    public void multipleWrongIPsRejected() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        // Deliver from multiple wrong IPs
        for (int i = 2; i <= 5; i++) {
            InetAddress wrongClient = InetAddress.getByName("127.0.0." + i);
            FakeSocket wrongSocket = new FakeSocket(wrongClient);
            wrongSocket.setCloseTracking(true);
            service.deliverAccepted(port, wrongSocket);
            assertEquals("Socket " + i + " should be closed", 1, wrongSocket.closeCalls);
        }

        Socket delivered = res.await(200);
        assertNull("Should not deliver any socket", delivered);
        assertEquals("Should count 4 rejections", 4, service.getRejectedConnections());
    }

    @Test
    public void rightIPAfterWrongIPs() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        // Deliver from wrong IPs first
        InetAddress wrong1 = InetAddress.getByName("127.0.0.2");
        InetAddress wrong2 = InetAddress.getByName("127.0.0.3");
        FakeSocket wrongSocket1 = new FakeSocket(wrong1);
        FakeSocket wrongSocket2 = new FakeSocket(wrong2);
        wrongSocket1.setCloseTracking(true);
        wrongSocket2.setCloseTracking(true);

        service.deliverAccepted(port, wrongSocket1);
        service.deliverAccepted(port, wrongSocket2);

        // Now deliver from correct IP
        FakeSocket correctSocket = new FakeSocket(client);
        service.deliverAccepted(port, correctSocket);

        Socket delivered = res.await(500);
        assertEquals("Should deliver correct socket", correctSocket, delivered);
        assertEquals("Wrong sockets should be closed", 1, wrongSocket1.closeCalls);
        assertEquals("Wrong sockets should be closed", 1, wrongSocket2.closeCalls);
        assertEquals("Should count 2 rejections", 2, service.getRejectedConnections());
        assertEquals("Should count 1 connection", 1, service.getTotalConnections());
    }

    @Test
    public void connectionToUnreservedPort() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        // Don't create any reservation
        InetAddress client = InetAddress.getByName("127.0.0.1");
        FakeSocket socket = new FakeSocket(client);
        socket.setCloseTracking(true);

        // Deliver connection with no reservation
        service.deliverAccepted(port, socket);

        // Socket should be closed
        assertEquals("Unreserved connection should be closed", 1, socket.closeCalls);
        assertEquals("Should count as rejection", 1, service.getRejectedConnections());
    }

    @Test
    public void cancelBeforeConnection() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        // Cancel before connection arrives
        service.cancel(res);

        // Now try to deliver connection
        FakeSocket socket = new FakeSocket(client);
        socket.setCloseTracking(true);
        service.deliverAccepted(port, socket);

        // Socket should be closed because reservation was cancelled
        assertEquals("Cancelled reservation socket should be closed", 1, socket.closeCalls);
    }

    @Test
    public void serviceStopClosesInFlightConnections() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client1 = InetAddress.getByName("127.0.0.1");
        InetAddress client2 = InetAddress.getByName("127.0.0.2");

        PassiveConnectionService.Reservation res1 = service.register(client1);
        PassiveConnectionService.Reservation res2 = service.register(client2);

        // Stop service before connections arrive
        service.stop();

        // Both should be cancelled
        assertTrue(res1.isCancelled());
        assertTrue(res2.isCancelled());

        assertNull(res1.await(100));
        assertNull(res2.await(100));
    }

    @Test
    public void repeatedStartStopCycle() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);

        for (int i = 0; i < 3; i++) {
            service.start();

            InetAddress client = InetAddress.getByName("127.0.0.1");
            PassiveConnectionService.Reservation res = service.register(client);

            FakeSocket socket = new FakeSocket(client);
            service.deliverAccepted(port, socket);

            Socket delivered = res.await(500);
            assertEquals("Cycle " + i + " should deliver socket", socket, delivered);

            service.stop();
        }
    }

    @Test
    public void socketCloseErrorHandled() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        InetAddress wrongClient = InetAddress.getByName("127.0.0.2");

        PassiveConnectionService.Reservation res = service.register(client);

        // Deliver socket that throws on close
        BrokenSocket brokenSocket = new BrokenSocket(wrongClient);
        service.deliverAccepted(port, brokenSocket);

        // Should handle the close error gracefully
        Socket delivered = res.await(200);
        assertNull("Should not deliver socket from wrong IP", delivered);

        // Cancel the first reservation to free up the slot
        service.cancel(res);

        // Service should still be operational
        PassiveConnectionService.Reservation res2 = service.register(client);
        FakeSocket goodSocket = new FakeSocket(client);
        service.deliverAccepted(port, goodSocket);
        assertEquals("Service should still work after close error", goodSocket, res2.await(500));
    }

    private int randomPort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static class FakeSocket extends Socket {
        private final InetAddress remote;
        private boolean trackClose;
        private int closeCalls;

        FakeSocket(InetAddress remote) {
            this.remote = remote;
        }

        void setCloseTracking(boolean trackClose) {
            this.trackClose = trackClose;
        }

        @Override
        public void close() throws IOException {
            if (trackClose) {
                closeCalls++;
            }
            super.close();
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress(remote, 12345);
        }
    }

    private static class BrokenSocket extends Socket {
        private final InetAddress remote;

        BrokenSocket(InetAddress remote) {
            this.remote = remote;
        }

        @Override
        public void close() throws IOException {
            throw new IOException("Simulated close error");
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress(remote, 12345);
        }
    }
}
