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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.util.IoUtils;

/**
 * Verifies that multiple clients on different IPs can share a single passive port
 * and still receive the correct data for their session.
 */
public class MultiplexPassivePortTest extends ClientTestTemplate {

    private int passivePort;

    @Override
    protected void setUp() throws Exception {
        passivePort = findFreePort();
        super.setUp();
        createTestFiles();
    }

    @Override
    protected boolean isConnectClient() {
        // we manage clients manually in the test
        return false;
    }

    @Override
    protected DataConnectionConfigurationFactory createDataConnectionConfigurationFactory() {
        DataConnectionConfigurationFactory dc = new DataConnectionConfigurationFactory();
        dc.setPassivePorts(String.valueOf(passivePort));
        dc.setPassiveIpCheck(true);
        dc.setMultiplexPassivePorts(true);
        return dc;
    }

    public void testMultipleClientsShareSinglePassivePort() throws Exception {
        List<String> clientIps = Arrays.asList(
                "127.0.0.2", "127.0.0.3", "127.0.0.4", "127.0.0.5", "127.0.0.6");

        if (!canBindAll(clientIps)) {
            // platform does not allow multiple loopback aliases; skip quietly
            return;
        }

        Map<String, String> expected = new HashMap<>();
        for (int i = 0; i < clientIps.size(); i++) {
            expected.put(clientIps.get(i), "data-for-" + i);
        }

        List<FTPClient> clients = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(clientIps.size());
        List<Future<String>> results = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(clientIps.size());

        try {
            int i = 0;
            for (String ip : clientIps) {
                FTPClient c = createBoundClient(ip);
                c.login(ADMIN_USERNAME, ADMIN_PASSWORD);
                c.enterLocalPassiveMode();
                clients.add(c);
                String fileName = "file" + i + ".txt";
                results.add(executor.submit(() -> {
                    ready.countDown();
                    ready.await();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    boolean ok = c.retrieveFile(fileName, out);
                    assertTrue("retrieveFile failed for " + ip, ok);
                    return out.toString("UTF-8");
                }));
                i++;
            }

            i = 0;
            for (Future<String> f : results) {
                String ip = clientIps.get(i++);
                assertEquals("Content mismatch for " + ip, expected.get(ip), f.get());
            }
        } finally {
            executor.shutdownNow();
            for (FTPClient c : clients) {
                try {
                    c.logout();
                } catch (Exception ignored) {
                }
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private FTPClient createBoundClient(String ip) throws Exception {
        FTPClient c = new FTPClient();
        c.setDefaultTimeout(10000);
        c.setSocketFactory(new BindingSocketFactory(ip));
        c.connect("127.0.0.1", getListenerPort());
        return c;
    }

    private void createTestFiles() throws IOException {
        int idx = 0;
        for (String ip : Arrays.asList("127.0.0.2", "127.0.0.3", "127.0.0.4", "127.0.0.5", "127.0.0.6")) {
            File target = new File(ROOT_DIR, "file" + idx + ".txt");
            writeFile(target, "data-for-" + idx);
            idx++;
        }
    }

    private void writeFile(File file, String content) throws IOException {
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes("UTF-8"));
        }
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

    private int findFreePort() throws IOException {
        ServerSocket ss = new ServerSocket(0);
        try {
            return ss.getLocalPort();
        } finally {
            ss.close();
        }
    }

    private static class BindingSocketFactory extends javax.net.SocketFactory {
        private final InetAddress localAddress;

        BindingSocketFactory(String ip) throws UnknownHostException {
            this.localAddress = InetAddress.getByName(ip);
        }

        /**
         * Used by Commons Net for data connections in passive mode.
         */
        @Override
        public Socket createSocket() throws IOException {
            Socket s = new Socket();
            s.bind(new InetSocketAddress(localAddress, 0));
            return s;
        }

        public Socket createSocket(String host, int port) throws IOException {
            Socket s = new Socket();
            s.bind(new InetSocketAddress(localAddress, 0));
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        public Socket createSocket(String host, int port, InetAddress localAddr, int localPort) throws IOException {
            Socket s = new Socket();
            InetAddress bindAddr = localAddr != null ? localAddr : localAddress;
            s.bind(new InetSocketAddress(bindAddr, localPort));
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket s = new Socket();
            s.bind(new InetSocketAddress(localAddress, 0));
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        public Socket createSocket(InetAddress address, int port, InetAddress localAddr, int localPort)
                throws IOException {
            Socket s = new Socket();
            InetAddress bindAddr = localAddr != null ? localAddr : localAddress;
            s.bind(new InetSocketAddress(bindAddr, localPort));
            s.connect(new InetSocketAddress(address, port));
            return s;
        }
    }
}
