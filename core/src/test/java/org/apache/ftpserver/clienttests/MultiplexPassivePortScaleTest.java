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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.impl.PassiveConnectionService;

/**
 * Strict end-to-end multiplex test with a highly restricted passive port range.
 * Verifies 100% success and per-client content integrity for hundreds of concurrent clients.
 */
public class MultiplexPassivePortScaleTest extends ClientTestTemplate {

    private static final int CLIENT_COUNT = 200;
    private static final int PASSIVE_PORT_COUNT = 3;

    private List<Integer> passivePorts;
    private final List<String> clientIps = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        passivePorts = findFreePorts(PASSIVE_PORT_COUNT);
        clientIps.clear();
        clientIps.addAll(generateClientIps(CLIENT_COUNT));

        super.setUp();
        createClientFiles();
    }

    @Override
    protected boolean isConnectClient() {
        return false;
    }

    @Override
    protected ConnectionConfigFactory createConnectionConfigFactory() {
        ConnectionConfigFactory factory = super.createConnectionConfigFactory();
        factory.setAnonymousLoginEnabled(false);
        factory.setMaxLogins(CLIENT_COUNT + 40);
        factory.setMaxThreads(CLIENT_COUNT + 40);
        return factory;
    }

    @Override
    protected DataConnectionConfigurationFactory createDataConnectionConfigurationFactory() {
        DataConnectionConfigurationFactory dc = new DataConnectionConfigurationFactory();
        dc.setPassivePorts(passivePortsAsString());
        dc.setPassiveIpCheck(true);
        dc.setMultiplexPassivePorts(true);
        dc.setIdleTime(30);
        return dc;
    }

    public void testTwoHundredClientsRestrictedPassivePortsNoMixing() throws Exception {
        if (!canBindAll(clientIps)) {
            // Environment does not support many loopback aliases needed for this host-based scale test.
            // Docker-native strict scale coverage exists in root test suite.
            return;
        }

        Set<String> uniqueIps = new HashSet<>(clientIps);
        assertEquals("Each client must use a distinct IP", CLIENT_COUNT, uniqueIps.size());

        Map<Integer, String> expectedShaByClient = expectedHashes();
        assertEquals("Each client content hash must be unique", CLIENT_COUNT,
                new HashSet<>(expectedShaByClient.values()).size());

        PassiveConnectionService service = server.getListener("default").getPassiveConnectionService();
        assertNotNull("PassiveConnectionService must be active for multiplex mode", service);

        AtomicInteger peakActiveReservations = new AtomicInteger(0);
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                peakActiveReservations.accumulateAndGet(service.getActiveReservations(), Math::max);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "multiplex-scale-sampler");
        sampler.setDaemon(true);
        sampler.start();

        ExecutorService executor = Executors.newFixedThreadPool(CLIENT_COUNT);
        CountDownLatch ready = new CountDownLatch(CLIENT_COUNT);
        List<Future<ClientDownloadResult>> futures = new ArrayList<>();
        List<FTPClient> clients = new ArrayList<>();

        try {
            for (int i = 0; i < CLIENT_COUNT; i++) {
                final int clientId = i;
                final String ip = clientIps.get(i);
                final String fileName = fileNameFor(clientId);
                final String expectedSha = expectedShaByClient.get(clientId);

                FTPClient ftpClient = createBoundClientAndConnect(ip);
                ftpClient.login(ADMIN_USERNAME, ADMIN_PASSWORD);
                ftpClient.setFileType(FTPClient.BINARY_FILE_TYPE);
                ftpClient.enterLocalPassiveMode();
                clients.add(ftpClient);

                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!ready.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent start");
                    }

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    boolean ok = ftpClient.retrieveFile(fileName, out);
                    if (!ok) {
                        throw new AssertionError("retrieveFile failed for client " + clientId + " (" + ip + ")");
                    }

                    String actualSha = sha256(out.toByteArray());
                    return new ClientDownloadResult(clientId, ip, fileName, expectedSha, actualSha);
                }));
            }

            List<ClientDownloadResult> results = new ArrayList<>();
            for (Future<ClientDownloadResult> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }

            assertEquals("Must have one result per client", CLIENT_COUNT, results.size());
            assertEquals("successCount must be 100%", CLIENT_COUNT,
                    results.stream().filter(r -> r.expectedSha().equals(r.actualSha())).count());

            Map<String, Integer> expectedOwnerByHash = new HashMap<>();
            for (Map.Entry<Integer, String> entry : expectedShaByClient.entrySet()) {
                expectedOwnerByHash.put(entry.getValue(), entry.getKey());
            }

            for (ClientDownloadResult result : results) {
                assertEquals("Client " + result.clientId() + " content mismatch", result.expectedSha(),
                        result.actualSha());
                Integer hashOwner = expectedOwnerByHash.get(result.actualSha());
                assertEquals("Client " + result.clientId() + " received content from another client",
                        result.clientId(), hashOwner.intValue());
            }

            assertTrue("Expected active reservations to exceed passive port count",
                    peakActiveReservations.get() > PASSIVE_PORT_COUNT);
        } finally {
            sampling.set(false);
            sampler.interrupt();
            sampler.join(2000);

            executor.shutdownNow();
            for (FTPClient client : clients) {
                try {
                    client.logout();
                } catch (Exception ignored) {
                }
                try {
                    client.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private List<String> generateClientIps(int count) {
        List<String> ips = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ips.add("127.0.0." + (i + 2));
        }
        return ips;
    }

    private String passivePortsAsString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < passivePorts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(passivePorts.get(i));
        }
        return sb.toString();
    }

    private void createClientFiles() throws Exception {
        for (int i = 0; i < CLIENT_COUNT; i++) {
            File target = new File(ROOT_DIR, fileNameFor(i));
            writeFile(target, contentFor(i));
        }
    }

    private Map<Integer, String> expectedHashes() throws Exception {
        Map<Integer, String> hashes = new HashMap<>();
        for (int i = 0; i < CLIENT_COUNT; i++) {
            hashes.put(i, sha256(contentFor(i).getBytes(StandardCharsets.UTF_8)));
        }
        return hashes;
    }

    private static String fileNameFor(int clientId) {
        return "client-" + clientId + ".bin";
    }

    private static String contentFor(int clientId) {
        String marker = "client=" + clientId + ";nonce=" + UUID.nameUUIDFromBytes(
                ("seed-" + clientId).getBytes(StandardCharsets.UTF_8));
        return (marker + "\n").repeat(128);
    }

    private static void writeFile(File file, String content) throws IOException {
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private FTPClient createBoundClientAndConnect(String ip) throws Exception {
        FTPClient client = new FTPClient();
        client.setDefaultTimeout(30000);
        client.setDataTimeout(30000);
        client.setSocketFactory(new BindingSocketFactory(ip));
        client.connect("127.0.0.1", getListenerPort());
        return client;
    }

    private boolean canBindAll(List<String> ips) {
        for (String ip : ips) {
            try (Socket s = new Socket()) {
                s.bind(new InetSocketAddress(InetAddress.getByName(ip), 0));
            } catch (BindException e) {
                return false;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> findFreePorts(int count) throws IOException {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                ServerSocket ss = new ServerSocket(0);
                ss.setReuseAddress(true);
                sockets.add(ss);
            }
            List<Integer> ports = new ArrayList<>();
            for (ServerSocket ss : sockets) {
                ports.add(ss.getLocalPort());
            }
            return ports;
        } finally {
            for (ServerSocket ss : sockets) {
                try {
                    ss.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static class BindingSocketFactory extends SocketFactory {
        private final InetAddress localAddress;

        BindingSocketFactory(String ip) throws UnknownHostException {
            this.localAddress = InetAddress.getByName(ip);
        }

        @Override
        public Socket createSocket() throws IOException {
            Socket s = new Socket();
            s.bind(new InetSocketAddress(localAddress, 0));
            return s;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket s = new Socket();
            s.bind(new InetSocketAddress(localAddress, 0));
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddr, int localPort) throws IOException {
            Socket s = new Socket();
            InetAddress bindAddr = localAddr != null ? localAddr : localAddress;
            s.bind(new InetSocketAddress(bindAddr, localPort));
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket s = new Socket();
            s.bind(new InetSocketAddress(localAddress, 0));
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddr, int localPort)
                throws IOException {
            Socket s = new Socket();
            InetAddress bindAddr = localAddr != null ? localAddr : localAddress;
            s.bind(new InetSocketAddress(bindAddr, localPort));
            s.connect(new InetSocketAddress(address, port));
            return s;
        }
    }

    private record ClientDownloadResult(int clientId, String ip, String fileName, String expectedSha, String actualSha) {
    }
}
