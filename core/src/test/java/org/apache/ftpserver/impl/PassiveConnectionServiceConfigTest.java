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
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.listener.Listener;
import org.apache.ftpserver.listener.ListenerFactory;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for PassiveConnectionService configuration and metrics.
 */
public class PassiveConnectionServiceConfigTest {

    private PassiveConnectionService service;

    @After
    public void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    public void customAcceptTimeout() throws Exception {
        int port = randomPort();
        // Use custom 2000ms timeout instead of default 1000ms
        service = new PassiveConnectionService(Collections.singleton(port), null, 2000);
        service.start();

        // Just verify it starts without errors - timeout is internal
        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);
        assertNotNull(res);
        service.cancel(res);
    }

    @Test
    public void invalidAcceptTimeoutRejected() throws Exception {
        int port = randomPort();
        try {
            service = new PassiveConnectionService(Collections.singleton(port), null, 0);
            fail("Should reject zero timeout");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            service = new PassiveConnectionService(Collections.singleton(port), null, -100);
            fail("Should reject negative timeout");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void invalidGlobalReservationCapRejected() throws Exception {
        Set<Integer> ports = new HashSet<>();
        ports.add(randomPort());
        ports.add(randomPort());
        try {
            service = new PassiveConnectionService(ports, null, 1000, 1);
            fail("Should reject global cap smaller than passive port count");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void defaultGlobalReservationCapScalesWithPortCount() throws Exception {
        Set<Integer> ports = new HashSet<>();
        ports.add(randomPort());
        ports.add(randomPort());
        ports.add(randomPort());

        service = new PassiveConnectionService(ports, null, 1000);

        assertEquals(ports.size() * 4096, service.getMaxTotalReservations());
    }

    @Test
    public void globalReservationCapIsEnforcedAcrossIps() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null, 1000, 1);
        service.start();

        InetAddress client1 = InetAddress.getByName("127.0.0.1");
        InetAddress client2 = InetAddress.getByName("127.0.0.2");

        PassiveConnectionService.Reservation res1 = service.register(client1);
        assertNotNull(res1);

        try {
            service.register(client2);
            fail("Should enforce max total passive reservations");
        } catch (DataConnectionException expected) {
            // expected
        }

        service.cancel(res1);

        PassiveConnectionService.Reservation res2 = service.register(client2);
        assertNotNull(res2);
        service.cancel(res2);
    }

    @Test
    public void metricsTrackReservations() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");

        // Initial state
        assertEquals(0, service.getTotalReservations());
        assertEquals(0, service.getActiveReservations());

        // Create reservation
        PassiveConnectionService.Reservation res = service.register(client);
        assertEquals(1, service.getTotalReservations());
        assertEquals(1, service.getActiveReservations());

        // Cancel reservation
        service.cancel(res);
        assertEquals(1, service.getTotalReservations());
        assertEquals(0, service.getActiveReservations());
        assertEquals(1, service.getCancelledReservations());
    }

    @Test
    public void metricsTrackConnections() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        PassiveConnectionService.Reservation res = service.register(client);

        // Simulate connection
        FakeSocket socket = new FakeSocket(client);
        service.deliverAccepted(port, socket);

        Socket delivered = res.await(500);
        assertNotNull(delivered);

        // Verify metrics
        assertEquals(1, service.getTotalConnections());
        assertEquals(0, service.getRejectedConnections());
    }

    @Test
    public void metricsTrackRejections() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");
        InetAddress wrongClient = InetAddress.getByName("127.0.0.2");

        PassiveConnectionService.Reservation res = service.register(client);

        // Deliver socket from wrong IP
        FakeSocket socket = new FakeSocket(wrongClient);
        service.deliverAccepted(port, socket);

        Socket delivered = res.await(200);
        // Should timeout or return null
        assertTrue(delivered == null);

        // Verify rejection was counted
        assertEquals(1, service.getRejectedConnections());
        assertEquals(0, service.getTotalConnections());
    }

    @Test
    public void metricsSurviveMultipleCycles() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        InetAddress client = InetAddress.getByName("127.0.0.1");

        for (int i = 0; i < 5; i++) {
            PassiveConnectionService.Reservation res = service.register(client);
            FakeSocket socket = new FakeSocket(client);
            service.deliverAccepted(port, socket);
            Socket delivered = res.await(500);
            assertNotNull("Cycle " + i + " should succeed", delivered);
        }

        assertEquals(5, service.getTotalReservations());
        assertEquals(5, service.getTotalConnections());
        assertEquals(0, service.getActiveReservations());
        assertEquals(0, service.getCancelledReservations());
    }

    @Test
    public void listenerUsesConfiguredGlobalReservationCap() throws Exception {
        int passivePort = randomPort();
        int configuredCap = 123;

        FtpServerFactory serverFactory = new FtpServerFactory();
        ListenerFactory listenerFactory = new ListenerFactory(serverFactory.getListener("default"));
        listenerFactory.setPort(0);

        DataConnectionConfigurationFactory dc = new DataConnectionConfigurationFactory();
        dc.setPassivePorts(String.valueOf(passivePort));
        dc.setMultiplexPassivePorts(true);
        dc.setMaxTotalPassiveReservations(configuredCap);
        listenerFactory.setDataConnectionConfiguration(dc.createDataConnectionConfiguration());
        Listener listener = listenerFactory.createListener();
        serverFactory.addListener("default", listener);

        FtpServer server = serverFactory.createServer();
        try {
            server.start();
            service = listener.getPassiveConnectionService();
            assertNotNull(service);
            assertEquals(configuredCap, service.getMaxTotalReservations());
        } finally {
            server.stop();
        }
    }

    private int randomPort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static class FakeSocket extends Socket {
        private final InetAddress remote;

        FakeSocket(InetAddress remote) {
            this.remote = remote;
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress(remote, 12345);
        }
    }
}
