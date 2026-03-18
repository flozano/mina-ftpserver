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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class PassiveConnectionServiceTest {

    private PassiveConnectionService service;

    @After
    public void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    public void registersAndDispatchesToMatchingIp() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        FakeSocket socket = new FakeSocket(client);
        service.deliverAccepted(port, socket);

        Socket delivered = res.await(500);
        assertNotNull(delivered);
        assertEquals(socket, delivered);
    }

    @Test
    public void dropsConnectionFromUnexpectedIp() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        FakeSocket socket = new FakeSocket(InetAddress.getByName("127.0.0.2"));
        socket.setCloseTracking(true);
        service.deliverAccepted(port, socket);

        Socket delivered = res.await(200);
        assertNull(delivered);
        // socket should be closed by service
        assertEquals(1, socket.closeCalls);
    }

    @Test
    public void enforcesPerIpCap() throws Exception {
        Set<Integer> ports = new HashSet<>();
        ports.add(randomPort());
        ports.add(randomPort());
        service = new PassiveConnectionService(ports, null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");

        service.register(client);
        service.register(client);

        try {
            service.register(client);
            fail("Expected DataConnectionException for per-IP cap");
        } catch (DataConnectionException expected) {
            // expected
        }
    }

    @Test
    public void cancelBeforeAwait() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        // Cancel before await
        service.cancel(res);

        // await should return null immediately due to early cancellation check
        long start = System.currentTimeMillis();
        Socket socket = res.await(5000);
        long elapsed = System.currentTimeMillis() - start;

        assertNull("Cancelled reservation should return null", socket);
        // Should return quickly, not wait for full timeout
        assert(elapsed < 1000);
    }

    @Test
    public void cancelDuringAwait() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        // Start await in separate thread
        Thread awaitThread = new Thread(() -> {
            try {
                Socket socket = res.await(10000);
                assertNull("Cancelled reservation should return null", socket);
            } catch (Exception e) {
                fail("Unexpected exception: " + e.getMessage());
            }
        });

        awaitThread.start();

        // Give await time to start
        Thread.sleep(100);

        // Cancel while awaiting
        service.cancel(res);

        // Thread should complete quickly
        awaitThread.join(1000);
        assert(!awaitThread.isAlive());
    }

    @Test
    public void cancelAfterConnection() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        FakeSocket socket = new FakeSocket(client);
        service.deliverAccepted(port, socket);

        Socket delivered = res.await(500);
        assertNotNull(delivered);

        // Cancel after connection is already established - should be safe
        service.cancel(res);
        // No exception should occur
    }

    @Test
    public void cancelReleasesSlotForReuse() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");

        // Register and cancel
        PassiveConnectionService.Reservation res1 = service.register(client);
        service.cancel(res1);

        // Should be able to register again on same port with same IP
        PassiveConnectionService.Reservation res2 = service.register(client);
        assertNotNull(res2);
    }

    @Test
    public void stopServiceCancelsAllReservations() throws Exception {
        Set<Integer> ports = new HashSet<>();
        ports.add(randomPort());
        ports.add(randomPort());
        service = new PassiveConnectionService(ports, null);
        service.start();

        InetAddress client1 = InetAddress.getByName("127.0.0.1");
        InetAddress client2 = InetAddress.getByName("127.0.0.2");

        PassiveConnectionService.Reservation res1 = service.register(client1);
        PassiveConnectionService.Reservation res2 = service.register(client2);

        // Stop service
        service.stop();

        // Both reservations should be cancelled
        assert(res1.isCancelled());
        assert(res2.isCancelled());

        // Awaits should return null
        assertNull(res1.await(100));
        assertNull(res2.await(100));
    }

    @Test
    public void reservationTimesOut() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        // Wait for timeout without connecting
        Socket socket = res.await(500);

        assertNull("Reservation should timeout if no connection arrives", socket);
    }

    @Test
    public void lateAcceptAfterTimeoutIsClosedOnCancel() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation reservation = service.register(client);

        Socket awaited = reservation.await(1);
        assertNull("Reservation should timeout", awaited);

        FakeSocket lateSocket = new FakeSocket(client);
        lateSocket.setCloseTracking(true);
        service.deliverAccepted(port, lateSocket);

        service.cancel(reservation);

        assertEquals("Late socket should be closed to avoid leak", 1, lateSocket.closeCalls);
    }

    @Test
    public void portExhaustion() throws Exception {
        // Create service with only 2 ports
        Set<Integer> ports = new HashSet<>();
        ports.add(randomPort());
        ports.add(randomPort());
        service = new PassiveConnectionService(ports, null);
        service.start();

        InetAddress client1 = InetAddress.getByName("127.0.0.1");

        // Reserve both ports for same IP (maxPerIp = 2, same as port count)
        PassiveConnectionService.Reservation res1 = service.register(client1);
        PassiveConnectionService.Reservation res2 = service.register(client1);

        assertNotNull(res1);
        assertNotNull(res2);

        // Third registration from same IP should fail - per-IP limit reached
        try {
            service.register(client1);
            fail("Expected DataConnectionException when per-IP limit is reached");
        } catch (DataConnectionException expected) {
            // Expected - per-IP limit reached (2 ports, so max 2 per IP)
        }

        // Cancel first reservation
        service.cancel(res1);

        // Now same client should be able to register again
        PassiveConnectionService.Reservation res3 = service.register(client1);
        assertNotNull(res3);
    }

    @Test
    public void portRecyclingAfterConnection() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client1 = InetAddress.getByName("127.0.0.1");
        InetAddress client2 = InetAddress.getByName("127.0.0.2");

        // Client 1 reserves port
        PassiveConnectionService.Reservation res1 = service.register(client1);

        // Client 2 cannot use same port yet (different IP can, but only one per port per IP)
        PassiveConnectionService.Reservation res2 = service.register(client2);
        assertNotNull(res2);

        // Complete first connection
        FakeSocket socket1 = new FakeSocket(client1);
        service.deliverAccepted(port, socket1);
        Socket delivered = res1.await(500);
        assertNotNull(delivered);

        // Now client1 should be able to register again on same port
        PassiveConnectionService.Reservation res3 = service.register(client1);
        assertNotNull(res3);
    }

    @Test
    public void concurrentSameIPRejected() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");

        // First reservation succeeds
        PassiveConnectionService.Reservation res1 = service.register(client);
        assertNotNull(res1);

        // Second reservation from same IP on same port should fail
        try {
            service.register(client);
            fail("Expected DataConnectionException for duplicate IP on same port");
        } catch (DataConnectionException expected) {
            // Expected
        }
    }

    @Test
    public void rapidConnectDisconnectCycle() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");

        // Perform multiple rapid register/cancel cycles
        for (int i = 0; i < 10; i++) {
            PassiveConnectionService.Reservation res = service.register(client);
            assertNotNull("Reservation " + i + " should succeed", res);
            service.cancel(res);
            // No delay - immediate retry
        }

        // Final reservation should still work
        PassiveConnectionService.Reservation finalRes = service.register(client);
        assertNotNull(finalRes);
    }

    @Test
    public void serviceRestartCleansState() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");

        // Create reservation
        PassiveConnectionService.Reservation res = service.register(client);
        assertNotNull(res);

        // Stop and restart service
        service.stop();
        service.start();

        // Should be able to create new reservation (old one should be cleaned up)
        PassiveConnectionService.Reservation newRes = service.register(client);
        assertNotNull(newRes);
        assert(res.isCancelled());
    }

    @Test
    public void minimumPortConfiguration() throws Exception {
        // Test with single port
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");

        PassiveConnectionService.Reservation res = service.register(client);
        assertNotNull(res);
        assertEquals("Should use the only available port", port, res.getPort());
    }

    @Test
    public void largePortSet() throws Exception {
        // Test with many ports to verify no performance issues.
        // Use random ports instead of a fixed range to avoid sporadic bind collisions on CI hosts.
        IllegalStateException lastBindError = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Set<Integer> ports = randomPorts(50);
            service = new PassiveConnectionService(ports, null);
            try {
                service.start();
                lastBindError = null;
                break;
            } catch (IllegalStateException e) {
                lastBindError = e;
                service.stop();
                service = null;
            }
        }
        if (lastBindError != null) {
            throw lastBindError;
        }

        InetAddress client = InetAddress.getByName("127.0.0.1");

        // Should be able to create many reservations
        List<PassiveConnectionService.Reservation> reservations = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            PassiveConnectionService.Reservation res = service.register(client);
            assertNotNull("Reservation " + i + " should succeed", res);
            reservations.add(res);
        }

        // Cleanup
        for (PassiveConnectionService.Reservation res : reservations) {
            service.cancel(res);
        }
    }

    private int randomPort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private Set<Integer> randomPorts(int count) throws IOException {
        Set<Integer> ports = new HashSet<>();
        while (ports.size() < count) {
            ports.add(randomPort());
        }
        return ports;
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
}
