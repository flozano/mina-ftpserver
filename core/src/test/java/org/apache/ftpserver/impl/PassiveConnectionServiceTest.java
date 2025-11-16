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
import java.util.HashSet;
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
}
