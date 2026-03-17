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

import java.io.ByteArrayInputStream;
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

import org.apache.commons.net.ftp.FTPSClient;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.ClientAuth;
import org.apache.ftpserver.ssl.SslConfigurationFactory;

/**
 * Verifies that multiple clients on different IPs can share a single passive port
 * with TLS/SSL encryption enabled.
 */
public class MultiplexPassivePortTLSTest extends ClientTestTemplate {

    private int passivePort;
    private static final byte[] TEST_DATA = "TESTDATA-TLS".getBytes();

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
    protected FtpServerFactory createServer() throws Exception {
        FtpServerFactory serverFactory = super.createServer();

        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(0);

        // Configure SSL
        SslConfigurationFactory sslFactory = new SslConfigurationFactory();
        sslFactory.setKeystoreFile(new File("src/test/resources/ftpserver.jks"));
        sslFactory.setKeystorePassword("password");
        sslFactory.setClientAuthentication(ClientAuth.WANT.name());

        listenerFactory.setSslConfiguration(sslFactory.createSslConfiguration());
        listenerFactory.setImplicitSsl(false); // Use explicit TLS

        // Configure data connection with multiplex
        DataConnectionConfigurationFactory dataConfig = createDataConnectionConfigurationFactory();
        listenerFactory.setDataConnectionConfiguration(dataConfig.createDataConnectionConfiguration());

        serverFactory.addListener("default", listenerFactory.createListener());

        return serverFactory;
    }

    @Override
    protected DataConnectionConfigurationFactory createDataConnectionConfigurationFactory() {
        DataConnectionConfigurationFactory dc = new DataConnectionConfigurationFactory();
        dc.setPassivePorts(String.valueOf(passivePort));
        dc.setPassiveIpCheck(true);
        dc.setMultiplexPassivePorts(true); // Enable multiplexing
        return dc;
    }

    public void testMultiplexWithTLS() throws Exception {
        List<String> clientIps = Arrays.asList(
                "127.0.0.2", "127.0.0.3", "127.0.0.4");

        if (!canBindAll(clientIps)) {
            // platform does not allow multiple loopback aliases; skip quietly
            return;
        }

        Map<String, String> expected = new HashMap<>();
        for (int i = 0; i < clientIps.size(); i++) {
            expected.put(clientIps.get(i), "data-for-tls-" + i);
        }

        List<FTPSClient> clients = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(clientIps.size());
        List<Future<String>> results = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(clientIps.size());

        try {
            int i = 0;
            for (String ip : clientIps) {
                FTPSClient c = createBoundTLSClient(ip);
                c.connect("127.0.0.1", getListenerPort());

                // Perform TLS handshake
                c.execAUTH("TLS");
                c.login(ADMIN_USERNAME, ADMIN_PASSWORD);

                // Enable TLS on data channel
                c.execPBSZ(0);
                c.execPROT("P");
                c.enterLocalPassiveMode();
                c.setRemoteVerificationEnabled(false);

                clients.add(c);
                String fileName = "file-tls-" + i + ".txt";
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
            for (FTPSClient c : clients) {
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

    public void testMultiplexWithTLSStoreFile() throws Exception {
        List<String> clientIps = Arrays.asList("127.0.0.2", "127.0.0.3");

        if (!canBindAll(clientIps)) {
            return;
        }

        List<FTPSClient> clients = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(clientIps.size());
        List<Future<Boolean>> results = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(clientIps.size());

        try {
            int i = 0;
            for (String ip : clientIps) {
                FTPSClient c = createBoundTLSClient(ip);
                c.connect("127.0.0.1", getListenerPort());
                c.execAUTH("TLS");
                c.login(ADMIN_USERNAME, ADMIN_PASSWORD);
                c.execPBSZ(0);
                c.execPROT("P");
                c.enterLocalPassiveMode();
                c.setRemoteVerificationEnabled(false);

                clients.add(c);
                String fileName = "upload-tls-" + i + ".txt";
                byte[] data = ("upload-data-" + i).getBytes("UTF-8");

                results.add(executor.submit(() -> {
                    ready.countDown();
                    ready.await();
                    return c.storeFile(fileName, new ByteArrayInputStream(data));
                }));
                i++;
            }

            for (Future<Boolean> f : results) {
                assertTrue("Store file failed", f.get());
            }
        } finally {
            executor.shutdownNow();
            for (FTPSClient c : clients) {
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

    private FTPSClient createBoundTLSClient(String ip) throws Exception {
        FTPSClient c = new FTPSClient(false); // Explicit TLS
        c.setDefaultTimeout(10000);
        c.setSocketFactory(new BindingSocketFactory(ip));
        c.setTrustManager(new org.apache.commons.net.util.TrustManagerUtils().getAcceptAllTrustManager());
        return c;
    }

    private void createTestFiles() throws IOException {
        int idx = 0;
        for (String ip : Arrays.asList("127.0.0.2", "127.0.0.3", "127.0.0.4")) {
            File target = new File(ROOT_DIR, "file-tls-" + idx + ".txt");
            writeFile(target, "data-for-tls-" + idx);
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
