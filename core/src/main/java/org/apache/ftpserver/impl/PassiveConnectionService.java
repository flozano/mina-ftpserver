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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Passive port acceptor that multiplexes passive ports per client IP.
 *
 * <strong>Internal class, do not use directly.</strong>
 */
public class PassiveConnectionService {

    public static class Reservation {
        private final int port;
        private final InetAddress clientAddress;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Socket socket;

        Reservation(int port, InetAddress clientAddress) {
            this.port = port;
            this.clientAddress = clientAddress;
        }

        int getPort() {
            return port;
        }

        InetAddress getClientAddress() {
            return clientAddress;
        }

        void complete(Socket socket) {
            this.socket = socket;
            latch.countDown();
        }

        Socket await(long timeoutMillis) throws DataConnectionException {
            try {
                if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataConnectionException("Interrupted while waiting for passive data connection", e);
            }

            return socket;
        }

        boolean cancel() {
            boolean result = cancelled.compareAndSet(false, true);
            latch.countDown();
            return result;
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }

    private final List<Integer> ports;
    private final InetAddress bindAddress;
    private final int maxPerIp;
    private final Map<Integer, ServerSocket> listeners = new HashMap<>();
    private final Map<Integer, Thread> listenerThreads = new HashMap<>();
    private final Map<Integer, Map<String, Reservation>> pendingByPort = new HashMap<>();
    private final Map<String, Integer> reservationsPerIp = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int nextPortIndex = 0;

    public PassiveConnectionService(Set<Integer> ports, InetAddress bindAddress) {
        if (ports == null || ports.isEmpty()) {
            throw new IllegalArgumentException("Passive ports are required");
        }
        this.ports = Collections.unmodifiableList(new ArrayList<>(ports));
        this.bindAddress = bindAddress;
        this.maxPerIp = ports.size();

        // prepare pending maps so register() can be used before start() in tests
        for (int port : ports) {
            pendingByPort.put(port, new HashMap<String, Reservation>());
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        for (int port : ports) {
            try {
                ServerSocket serverSocket = new ServerSocket(port, 0, bindAddress);
                serverSocket.setReuseAddress(true);
                serverSocket.setSoTimeout(1000);
                listeners.put(port, serverSocket);

                Thread t = new Thread(new AcceptLoop(serverSocket, port), "ftp-passive-" + port);
                t.setDaemon(true);
                t.start();
                listenerThreads.put(port, t);
            } catch (Exception e) {
                stop();
                throw new IllegalStateException("Failed to bind passive port " + port, e);
            }
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        for (ServerSocket ss : listeners.values()) {
            try {
                ss.close();
            } catch (Exception ignored) {
            }
        }

        for (Thread t : listenerThreads.values()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        listeners.clear();
        listenerThreads.clear();

        // cancel any remaining reservations
        synchronized (this) {
            for (Map<String, Reservation> perPort : pendingByPort.values()) {
                for (Reservation res : perPort.values()) {
                    res.cancel();
                }
                perPort.clear();
            }
            reservationsPerIp.clear();
        }
    }

    /**
     * Register a passive connection slot for the given client.
     *
     * @param clientAddress the client IP address that owns the reservation
     * @return the created reservation holding the selected port
     */
    public Reservation register(InetAddress clientAddress) throws DataConnectionException {
        String key = clientAddress.getHostAddress();

        synchronized (this) {
            Integer perIp = reservationsPerIp.get(key);
            if (perIp != null && perIp >= maxPerIp) {
                throw new DataConnectionException("Maximum passive connections reached for " + key);
            }

            int port = selectPort();

            Map<String, Reservation> perPort = pendingByPort.get(port);
            if (perPort == null) {
                throw new DataConnectionException("Passive port " + port + " is not available");
            }

            if (perPort.containsKey(key)) {
                throw new DataConnectionException("Passive port already pending for " + key);
            }

            Reservation reservation = new Reservation(port, clientAddress);
            perPort.put(key, reservation);
            reservationsPerIp.put(key, perIp == null ? 1 : perIp + 1);
            return reservation;
        }
    }

    public void cancel(Reservation reservation) {
        if (reservation == null) {
            return;
        }

        synchronized (this) {
            Map<String, Reservation> perPort = pendingByPort.get(reservation.getPort());
            if (perPort != null) {
                String key = reservation.getClientAddress().getHostAddress();
                Reservation removed = perPort.remove(key);
                if (removed != null) {
                    decrementPerIp(key);
                    removed.cancel();
                }
            }
        }
    }

    private void decrementPerIp(String key) {
        Integer current = reservationsPerIp.get(key);
        if (current == null) {
            return;
        }
        if (current <= 1) {
            reservationsPerIp.remove(key);
        } else {
            reservationsPerIp.put(key, current - 1);
        }
    }

    private int selectPort() {
        // simple round-robin selection
        int index = nextPortIndex++ % ports.size();
        if (index < 0) {
            index += ports.size();
        }
        return ports.get(index);
    }

    /**
     * Visible for tests: handle an accepted socket without going through the real accept loop.
     */
    void deliverAccepted(int port, Socket socket) {
        handleAccepted(port, socket);
    }

    private class AcceptLoop implements Runnable {
        private final ServerSocket serverSocket;
        private final int port;

        AcceptLoop(ServerSocket serverSocket, int port) {
            this.serverSocket = serverSocket;
            this.port = port;
        }

        public void run() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    handleAccepted(port, socket);
                } catch (java.net.SocketTimeoutException ste) {
                    // ignore and keep loop alive
                } catch (Exception e) {
                    if (running.get()) {
                        // log and continue
                    }
                }
            }
        }
    }

    private void handleAccepted(int port, Socket socket) {
        InetAddress remoteAddress = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress();
        String key = remoteAddress.getHostAddress();
        Reservation reservation;

        synchronized (this) {
            Map<String, Reservation> perPort = pendingByPort.get(port);
            reservation = perPort != null ? perPort.remove(key) : null;
            if (reservation != null) {
                decrementPerIp(key);
            }
        }

        try {
            if (reservation != null && !reservation.isCancelled()) {
                reservation.complete(socket);
            } else {
                socket.close();
            }
        } catch (Exception e) {
            // best effort close
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
