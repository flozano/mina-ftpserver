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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Tests PassiveConnectionService with IPv6 addresses to ensure proper
 * normalization and handling of different IPv6 address representations.
 */
public class PassiveConnectionServiceIPv6Test {

    private PassiveConnectionService service;

    @After
    public void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    public void testIPv6LocalhostVariants() throws Exception {
        if (!isIPv6Available()) {
            // Skip test if IPv6 is not available
            return;
        }

        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        // Test that different representations of the same IPv6 address
        // are treated as the same client
        InetAddress loopbackCompressed = InetAddress.getByName("::1");
        InetAddress loopbackFull = InetAddress.getByName("0:0:0:0:0:0:0:1");

        // Both should refer to the same address
        PassiveConnectionService.Reservation res1 = service.register(loopbackCompressed);
        assertNotNull(res1);

        // Second reservation with different string representation should fail
        // because it's the same IP address
        try {
            service.register(loopbackFull);
            fail("Expected DataConnectionException for same IP with different representation");
        } catch (DataConnectionException expected) {
            // This is expected - same IP should not get two reservations on same port
        }

        // Cancel the first reservation
        service.cancel(res1);

        // Now we should be able to register with the "different" representation
        PassiveConnectionService.Reservation res2 = service.register(loopbackFull);
        assertNotNull(res2);
    }

    @Test
    public void testIPv6PerIpLimit() throws Exception {
        if (!isIPv6Available()) {
            return;
        }

        Set<Integer> ports = new HashSet<>();
        ports.add(randomPort());
        ports.add(randomPort());

        service = new PassiveConnectionService(ports, null);
        service.start();

        InetAddress ipv6Loopback = InetAddress.getByName("::1");

        // Register two reservations (one per port)
        PassiveConnectionService.Reservation res1 = service.register(ipv6Loopback);
        PassiveConnectionService.Reservation res2 = service.register(ipv6Loopback);

        // Third should fail (per-IP limit is 2, same as number of ports)
        try {
            service.register(ipv6Loopback);
            fail("Expected DataConnectionException for IPv6 per-IP cap");
        } catch (DataConnectionException expected) {
            // Expected
        }
    }

    @Test
    public void testIPv6ConnectionRouting() throws Exception {
        if (!isIPv6Available()) {
            return;
        }

        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress ipv6Client = InetAddress.getByName("::1");
        PassiveConnectionService.Reservation res = service.register(ipv6Client);

        // Simulate connection from the same IPv6 address
        FakeSocket socket = new FakeSocket(ipv6Client);
        service.deliverAccepted(port, socket);

        Socket delivered = res.await(500);
        assertNotNull("Socket should be delivered to matching IPv6 reservation", delivered);
        assertEquals(socket, delivered);
    }

    @Test
    public void testIPv6UnexpectedConnection() throws Exception {
        if (!isIPv6Available()) {
            return;
        }

        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress ipv6Client1 = InetAddress.getByName("::1");
        InetAddress ipv6Client2 = InetAddress.getByName("::2");

        PassiveConnectionService.Reservation res = service.register(ipv6Client1);

        // Connection from different IPv6 address should be rejected
        FakeSocket socket = new FakeSocket(ipv6Client2);
        socket.setCloseTracking(true);
        service.deliverAccepted(port, socket);

        Socket delivered = res.await(200);
        assertNull("Socket from unexpected IPv6 should not be delivered", delivered);
        assertEquals("Socket should be closed", 1, socket.closeCalls);
    }

    @Test
    public void testMixedIPv4IPv6() throws Exception {
        if (!isIPv6Available()) {
            return;
        }

        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress ipv4Client = InetAddress.getByName("127.0.0.1");
        InetAddress ipv6Client = InetAddress.getByName("::1");

        // Both IPv4 and IPv6 should be able to use the same port
        PassiveConnectionService.Reservation res4 = service.register(ipv4Client);
        PassiveConnectionService.Reservation res6 = service.register(ipv6Client);

        assertNotNull(res4);
        assertNotNull(res6);

        // Deliver to IPv4
        FakeSocket socket4 = new FakeSocket(ipv4Client);
        service.deliverAccepted(port, socket4);

        Socket delivered4 = res4.await(500);
        assertNotNull(delivered4);

        // Deliver to IPv6
        FakeSocket socket6 = new FakeSocket(ipv6Client);
        service.deliverAccepted(port, socket6);

        Socket delivered6 = res6.await(500);
        assertNotNull(delivered6);
    }

    @Test
    public void testIPv6MappedIPv4IsSameReservationKeyAsIPv4() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress ipv4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        InetAddress ipv6Mapped = createMappedIpv4Address(127, 0, 0, 1);

        PassiveConnectionService.Reservation reservation = service.register(ipv4);
        assertNotNull(reservation);

        try {
            service.register(ipv6Mapped);
            fail("Expected DataConnectionException for IPv4-mapped IPv6 duplicate reservation");
        } catch (DataConnectionException expected) {
            // expected
        }

        FakeSocket socket = new FakeSocket(ipv6Mapped);
        service.deliverAccepted(port, socket);

        Socket delivered = reservation.await(500);
        assertNotNull("IPv4 reservation should match IPv4-mapped IPv6 accepted socket", delivered);
        assertEquals(socket, delivered);
    }

    @Test
    public void testIPv4ConnectionMatchesIPv6MappedIPv4Reservation() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress ipv4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        InetAddress ipv6Mapped = createMappedIpv4Address(127, 0, 0, 1);

        PassiveConnectionService.Reservation reservation = service.register(ipv6Mapped);
        assertNotNull(reservation);

        FakeSocket socket = new FakeSocket(ipv4);
        service.deliverAccepted(port, socket);

        Socket delivered = reservation.await(500);
        assertNotNull("IPv4-mapped IPv6 reservation should match IPv4 accepted socket", delivered);
        assertEquals(socket, delivered);
    }

    private boolean isIPv6Available() {
        try {
            InetAddress ipv6 = InetAddress.getByName("::1");
            return ipv6 instanceof Inet6Address;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private int randomPort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private InetAddress createMappedIpv4Address(int a, int b, int c, int d) throws UnknownHostException {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = (byte) a;
        mapped[13] = (byte) b;
        mapped[14] = (byte) c;
        mapped[15] = (byte) d;
        return Inet6Address.getByAddress(null, mapped, -1);
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
